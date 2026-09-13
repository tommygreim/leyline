package leyline.native.frontdoor.wire

import com.google.protobuf.Any
import com.google.protobuf.CodedOutputStream
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.native.NativeTag
import java.io.ByteArrayOutputStream

class FdEnvelopeTest :
    FunSpec({
        tags(NativeTag)

        val transactionId = "11111111-1111-1111-1111-111111111111"
        val typeUrl = "type.googleapis.com/leyline.test.BootstrapRequest"
        val payload =
            Any
                .newBuilder()
                .setTypeUrl(typeUrl)
                .build()
                .toByteArray()

        fun encode(block: CodedOutputStream.() -> Unit): ByteArray {
            val buffer = ByteArrayOutputStream()
            CodedOutputStream.newInstance(buffer).apply {
                block()
                flush()
            }
            return buffer.toByteArray()
        }

        test("typed Request is recognized without a JSON field") {
            val message =
                FdEnvelope.decode(
                    encode {
                        writeUInt32(1, 1)
                        writeString(2, transactionId)
                        writeString(3, "bootstrap")
                        writeByteArray(4, payload)
                    },
                )

            message.envelopeType shouldBe FdEnvelope.EnvelopeType.REQUEST
            message.key shouldBe "bootstrap"
            message.protobufTypeUrl shouldBe typeUrl
            message.jsonPayload shouldBe null
        }

        test("typed Cmd exposes its Any type without treating it as JSON") {
            val message =
                FdEnvelope.decode(
                    encode {
                        writeUInt32(1, 1)
                        writeString(2, transactionId)
                        writeByteArray(3, payload)
                    },
                )

            message.envelopeType shouldBe FdEnvelope.EnvelopeType.CMD
            message.protobufTypeUrl shouldBe typeUrl
            message.jsonPayload shouldBe null
        }

        test("Cmd compression flag does not turn a JSON Cmd into a Request") {
            val message =
                FdEnvelope.decode(
                    encode {
                        writeUInt32(1, 1)
                        writeString(2, transactionId)
                        writeString(4, "{}")
                        writeBool(5, false)
                    },
                )

            message.envelopeType shouldBe FdEnvelope.EnvelopeType.CMD
            message.jsonPayload shouldBe "{}"
            message.protobufTypeUrl shouldBe null
        }

        test("typed Response exposes the Any type for protocol diagnostics") {
            val message = FdEnvelope.decode(FdEnvelope.encodeRawProtoResponse(transactionId, payload))
            message.envelopeType shouldBe FdEnvelope.EnvelopeType.RESPONSE
            message.protobufTypeUrl shouldBe typeUrl
            message.jsonPayload shouldBe null
        }
    })
