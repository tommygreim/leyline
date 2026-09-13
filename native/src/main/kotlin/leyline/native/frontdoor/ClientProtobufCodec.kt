package leyline.native.frontdoor

import com.google.protobuf.Any
import com.google.protobuf.AnyProto
import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.DynamicMessage
import com.google.protobuf.StructProto
import com.google.protobuf.TimestampProto
import com.google.protobuf.WrappersProto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.Base64

/** Encodes synthetic server state using descriptors from the locally installed client. */
class ClientProtobufCodec private constructor(
    private val messages: Map<String, Descriptor>,
) {
    fun pack(
        typeName: String,
        payload: String,
    ): ByteArray {
        val descriptor = messages[typeName] ?: error("Client schema has no message $typeName")
        val value = encodeMessage(descriptor, Json.parseToJsonElement(payload), typeName)
        return Any
            .newBuilder()
            .setTypeUrl("type.googleapis.com/$typeName")
            .setValue(value.toByteString())
            .build()
            .toByteArray()
    }

    private fun encodeMessage(
        descriptor: Descriptor,
        source: JsonElement,
        location: String,
    ): DynamicMessage {
        val builder = DynamicMessage.newBuilder(descriptor)
        if (descriptor.file.name == "google/protobuf/wrappers.proto" && source is JsonPrimitive) {
            val valueField = descriptor.findFieldByName("value")
            builder.setField(valueField, encodeValue(valueField, source, location))
            return builder.build()
        }
        if (descriptor.fullName == "google.protobuf.Timestamp" && source is JsonPrimitive) {
            val instant = OffsetDateTime.parse(source.content).toInstant()
            builder.setField(descriptor.findFieldByName("seconds"), instant.epochSecond)
            builder.setField(descriptor.findFieldByName("nanos"), instant.nano)
            return builder.build()
        }
        val obj = source as? JsonObject ?: error("$location expects an object, received ${source::class.simpleName}")
        val recognized = descriptor.fields.flatMap { listOf(normalize(it.name), normalize(it.jsonName)) }.toSet()
        // Legacy cosmetic JSON carries a discriminator already represented by
        // the protobuf message type. All other unsupported fields remain errors.
        val unknown = obj.keys.filter { normalize(it) !in recognized && !(it == "Type" && descriptor.fullName in COSMETIC_TYPES) }
        require(unknown.isEmpty()) { "$location has fields absent from the client schema: ${unknown.joinToString()}" }
        for (field in descriptor.fields) {
            val value =
                obj[field.name] ?: obj[field.jsonName]
                    ?: obj.entries.firstOrNull { normalize(it.key) == normalize(field.name) }?.value
            if (value == null || value is JsonNull) continue
            val fieldLocation = "$location.${field.name}"
            when {
                field.isMapField -> {
                    val map = value as? JsonObject ?: error("$fieldLocation expects a map object")
                    val keyField = field.messageType.findFieldByName("key")
                    val valueField = field.messageType.findFieldByName("value")
                    for ((key, item) in map) {
                        val entry =
                            DynamicMessage
                                .newBuilder(field.messageType)
                                .setField(keyField, encodeValue(keyField, JsonPrimitive(key), "$fieldLocation.key"))
                                .setField(valueField, encodeValue(valueField, item, "$fieldLocation.value"))
                                .build()
                        builder.addRepeatedField(field, entry)
                    }
                }
                field.isRepeated -> {
                    val list = value as? JsonArray ?: error("$fieldLocation expects an array")
                    for (item in list) builder.addRepeatedField(field, encodeValue(field, item, fieldLocation))
                }
                else -> builder.setField(field, encodeValue(field, value, fieldLocation))
            }
        }
        return builder.build()
    }

    private fun encodeValue(
        field: FieldDescriptor,
        value: JsonElement,
        location: String,
    ): kotlin.Any {
        if (field.javaType == FieldDescriptor.JavaType.MESSAGE) return encodeMessage(field.messageType, value, location)
        val scalar = value as? JsonPrimitive ?: error("$location expects a scalar")
        return try {
            when (field.javaType) {
                FieldDescriptor.JavaType.INT -> scalar.int
                FieldDescriptor.JavaType.LONG -> scalar.long
                FieldDescriptor.JavaType.FLOAT -> scalar.float
                FieldDescriptor.JavaType.DOUBLE -> scalar.double
                FieldDescriptor.JavaType.BOOLEAN -> scalar.boolean
                FieldDescriptor.JavaType.STRING -> scalar.content
                FieldDescriptor.JavaType.BYTE_STRING -> ByteString.copyFrom(Base64.getDecoder().decode(scalar.content))
                FieldDescriptor.JavaType.ENUM ->
                    field.enumType.findValueByName(scalar.content)
                        ?: scalar.content.toIntOrNull()?.let { field.enumType.findValueByNumber(it) }
                        ?: error("$location has unknown enum value ${scalar.content}")
                else -> error("Unsupported protobuf field type at $location")
            }
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$location cannot be encoded as ${field.javaType}", error)
        }
    }

    companion object {
        private val COSMETIC_TYPES =
            setOf(
                "Wizards.Arena.Models.Network.CosmeticsAvatarEntry",
                "Wizards.Arena.Models.Network.CosmeticsSleeveEntry",
                "Wizards.Arena.Models.Network.CosmeticPetEntry",
                "Wizards.Arena.Models.Network.CosmeticEmoteEntry",
                "Wizards.Arena.Models.Network.CosmeticTitleEntry",
            )

        fun load(path: Path): ClientProtobufCodec = fromDescriptors(FileDescriptorSet.parseFrom(Files.readAllBytes(path)))

        internal fun fromDescriptors(set: FileDescriptorSet): ClientProtobufCodec {
            val files = set.fileList.associateBy { it.name }
            val resolved =
                listOf(AnyProto.getDescriptor(), StructProto.getDescriptor(), TimestampProto.getDescriptor(), WrappersProto.getDescriptor())
                    .associateBy { it.name }
                    .toMutableMap()
            val resolving = mutableSetOf<String>()

            fun resolve(name: String): FileDescriptor {
                resolved[name]?.let { return it }
                check(resolving.add(name)) { "Cyclic client protobuf descriptor dependency: $name" }
                val file = files[name] ?: error("Missing client protobuf descriptor dependency: $name")
                val dependencies = file.dependencyList.map { resolve(it) }.toTypedArray()
                val descriptor = FileDescriptor.buildFrom(file, dependencies)
                resolving.remove(name)
                resolved[name] = descriptor
                return descriptor
            }
            files.keys.forEach { resolve(it) }
            val messages = mutableMapOf<String, Descriptor>()

            fun index(message: Descriptor) {
                messages[message.fullName] = message
                message.nestedTypes.forEach { index(it) }
            }
            resolved.values.forEach { file -> file.messageTypes.forEach { index(it) } }
            return ClientProtobufCodec(messages)
        }

        private fun normalize(name: String): String = name.removeSuffix("Internal").filter { it.isLetterOrDigit() }.lowercase()
    }
}
