package leyline.native.frontdoor

import com.google.protobuf.UnknownFieldSet
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.domain.service.EventRegistry
import leyline.native.NativeTag

class FdProtoBuilderTest :
    FunSpec({

        tags(NativeTag)

        test("buildFormatsProto produces valid Any-wrapped proto with formats and groups") {
            val bytes = FdProtoBuilder.buildFormatsProto()
            val any = UnknownFieldSet.parseFrom(bytes)

            // field 1 = type_url
            val typeUrl =
                any
                    .getField(1)
                    .lengthDelimitedList
                    .first()
                    .toStringUtf8()
            typeUrl shouldBe "type.googleapis.com/Wizards.Arena.Models.Network.GetFormatsResponse"

            // field 2 = inner message bytes
            val inner = UnknownFieldSet.parseFrom(any.getField(2).lengthDelimitedList.first())
            // field 1 = format entries, field 2 = format groups
            val formatCount = inner.getField(1).lengthDelimitedList.size
            val groupCount = inner.getField(2).lengthDelimitedList.size

            formatCount shouldBeGreaterThan 5
            groupCount shouldBe 3 // EvergreenFormats, ConstructedSortOrder, BannedFormats
            val setCodes =
                inner
                    .getField(1)
                    .lengthDelimitedList
                    .flatMap { UnknownFieldSet.parseFrom(it).getField(3).lengthDelimitedList }
                    .map { it.toStringUtf8() }
            setCodes shouldContainAll listOf("HOB", "HOC", "SPM", "MSH")
            setCodes shouldNotContain "OM1"
        }

        test("every active event deck selection format has supplied format metadata") {
            val any = UnknownFieldSet.parseFrom(FdProtoBuilder.buildFormatsProto())
            val inner = UnknownFieldSet.parseFrom(any.getField(2).lengthDelimitedList.single())
            val formatNames =
                inner
                    .getField(1)
                    .lengthDelimitedList
                    .map {
                        UnknownFieldSet
                            .parseFrom(it)
                            .getField(1)
                            .lengthDelimitedList
                            .single()
                            .toStringUtf8()
                    }.toSet()
            val unresolved =
                EventRegistry.activeEvents
                    .filter { it.deckSelectFormat !in formatNames }
                    .map { "${it.internalName}: ${it.deckSelectFormat}" }

            withClue("active events with no format metadata cause deck selection to dereference a null Format") {
                unresolved.shouldBeEmpty()
            }
        }

        test("buildSetsProto produces valid Any-wrapped proto with sets and groups") {
            val bytes = FdProtoBuilder.buildSetsProto()
            val any = UnknownFieldSet.parseFrom(bytes)

            val typeUrl =
                any
                    .getField(1)
                    .lengthDelimitedList
                    .first()
                    .toStringUtf8()
            typeUrl shouldBe "type.googleapis.com/Wizards.Arena.Models.Network.SetMetadataResponse"

            val inner = UnknownFieldSet.parseFrom(any.getField(2).lengthDelimitedList.first())
            // field 1 = set entries, field 2 = set groups
            val setCount = inner.getField(1).lengthDelimitedList.size
            val groupCount = inner.getField(2).lengthDelimitedList.size

            setCount shouldBeGreaterThan 50 // 109 sets in metadata
            groupCount shouldBe 1 // AllFilters
            val setCodes =
                inner
                    .getField(1)
                    .lengthDelimitedList
                    .map {
                        UnknownFieldSet
                            .parseFrom(it)
                            .getField(1)
                            .lengthDelimitedList
                            .single()
                            .toStringUtf8()
                    }
            setCodes shouldContainAll listOf("HOB", "HOC", "SPM", "MSH", "OM1")
            val filterCodes =
                UnknownFieldSet
                    .parseFrom(inner.getField(2).lengthDelimitedList.single())
                    .getField(2)
                    .lengthDelimitedList
                    .map { it.toStringUtf8() }
            filterCodes shouldContainAll listOf("HOB", "HOC", "SPM", "MSH")
            filterCodes shouldNotContain "OM1"
        }
    })
