package leyline.game.data

import forge.StaticData
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.BoardTest
import java.io.File

class ForgeReleaseCatalogTest :
    BoardTest({
        test("release catalog includes FRA and FRC rather than their early partial lists") {
            val data = StaticData.instance()
            for ((code, names) in mapOf(
                "FRA" to listOf("Enlightened Confidant", "Stingcaster Mage", "Craterclaw Colossus"),
                "FRC" to listOf("Jace, Multiverse Architect", "Nissa, Leyline Tamer", "Omnath, Locus of the Void"),
            )) {
                withClue(code) {
                    val edition = data.editions.get(code)
                    edition shouldNotBe null
                    edition.allCardsInSet.size shouldBeGreaterThan if (code == "FRA") 200 else 70
                    for (name in names) {
                        withClue(name) {
                            val card = data.commonCards.getCard(name, code)
                            card shouldNotBe null
                            card.edition shouldBe code
                        }
                    }
                }
            }
        }

        test("release migration does not retain obsolete upcoming copies of canonical scripts") {
            val root = File(System.getProperty("leyline.content.root"), "forge/forge-gui/res/cardsfolder")
            root.isDirectory shouldBe true
            val byName = mutableMapOf<String, MutableList<String>>()
            for (file in root.walkTopDown().filter { it.isFile && it.extension == "txt" }) {
                val name = file.useLines { lines -> lines.firstOrNull { it.startsWith("Name:") }?.removePrefix("Name:") }
                if (name != null) byName.getOrPut(name) { mutableListOf() }.add(file.relativeTo(root).path)
            }
            byName.filterValues { it.size > 1 } shouldBe emptyMap()
        }
    })
