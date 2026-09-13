package leyline.native.frontdoor

import com.google.protobuf.Any
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.Timestamp
import com.google.protobuf.UInt32Value
import com.google.protobuf.UnknownFieldSet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.native.NativeTag
import java.time.Instant

class ClientProtobufCodecTest :
    FunSpec({
        tags(NativeTag)

        fun field(
            name: String,
            number: Int,
            type: Type,
            typeName: String? = null,
            repeated: Boolean = false,
        ): FieldDescriptorProto =
            FieldDescriptorProto
                .newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(if (repeated) Label.LABEL_REPEATED else Label.LABEL_OPTIONAL)
                .apply { if (typeName != null) setTypeName(typeName) }
                .build()

        val entry =
            DescriptorProto
                .newBuilder()
                .setName("ItemsInternalEntry")
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(field("key", 1, Type.TYPE_STRING))
                .addField(field("value", 2, Type.TYPE_INT32))
        val catalog =
            DescriptorProto
                .newBuilder()
                .setName("Catalog")
                .addNestedType(entry)
                .addField(field("ItemsInternal", 1, Type.TYPE_MESSAGE, ".leyline.test.Catalog.ItemsInternalEntry", repeated = true))
                .addField(field("ResetTimestampInternal", 2, Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
                .addField(field("Notes", 3, Type.TYPE_STRING, repeated = true))
                .addField(field("TileId", 4, Type.TYPE_MESSAGE, ".google.protobuf.UInt32Value"))
        val file =
            FileDescriptorProto
                .newBuilder()
                .setName("synthetic-catalog.proto")
                .setPackage("leyline.test")
                .setSyntax("proto3")
                .addDependency("google/protobuf/timestamp.proto")
                .addDependency("google/protobuf/wrappers.proto")
                .addMessageType(catalog)
        val descriptors = FileDescriptorSet.newBuilder().addFile(file).build()
        val codec = ClientProtobufCodec.fromDescriptors(descriptors)

        test("synthetic JSON becomes a typed Any with maps and normalized timestamp fields") {
            val payload = """{"Items":{"sample":250},"_resetTimestamp":"2026-01-02T03:04:05Z","Notes":["ready"],"TileId":123}"""
            val any = Any.parseFrom(codec.pack("leyline.test.Catalog", payload))
            any.typeUrl shouldBe "type.googleapis.com/leyline.test.Catalog"
            val message = UnknownFieldSet.parseFrom(any.value)
            val item = UnknownFieldSet.parseFrom(message.getField(1).lengthDelimitedList.single())
            item
                .getField(1)
                .lengthDelimitedList
                .single()
                .toStringUtf8() shouldBe "sample"
            item.getField(2).varintList.single() shouldBe 250L
            val timestamp = Timestamp.parseFrom(message.getField(2).lengthDelimitedList.single())
            timestamp.seconds shouldBe Instant.parse("2026-01-02T03:04:05Z").epochSecond
            message
                .getField(3)
                .lengthDelimitedList
                .single()
                .toStringUtf8() shouldBe "ready"
            UInt32Value.parseFrom(message.getField(4).lengthDelimitedList.single()).value shouldBe 123
        }

        test("unrecognized bootstrap fields fail with the message name") {
            val failure = shouldThrow<IllegalArgumentException> { codec.pack("leyline.test.Catalog", """{"Unknown":1}""") }
            failure.message shouldContain "leyline.test.Catalog"
            failure.message shouldContain "Unknown"
        }

        test("incompatible repeated field shape fails with the field path") {
            val failure = shouldThrow<IllegalStateException> { codec.pack("leyline.test.Catalog", """{"Notes":{}}""") }
            failure.message shouldContain "leyline.test.Catalog.Notes expects an array"
        }

        test("missing descriptor dependencies are reported explicitly") {
            val broken =
                FileDescriptorSet
                    .newBuilder()
                    .addFile(file.clone().addDependency("missing.proto"))
                    .build()
            val failure = shouldThrow<IllegalStateException> { ClientProtobufCodec.fromDescriptors(broken) }
            failure.message shouldContain "missing.proto"
        }
    })
