package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.sql.DriverManager

/**
 * Verifies name + set resolution against a minimal stand-in card DB with the
 * same schema shape the client publishes (Cards + Localizations_enUS).
 *
 * Covers the Universes Within case: those printings are flagged
 * IsPrimaryCard=0, so an explicit set code must still resolve them.
 */
class SqliteCardRepositoryNameLookupTest :
    FunSpec({

        tags(UnitTag)

        fun insertCard(
            url: String,
            grpId: Int,
            titleId: Int,
            name: String,
            expansion: String,
            isPrimary: Int,
        ) {
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("INSERT INTO Localizations_enUS(LocId, Formatted, Loc) VALUES ($titleId, 1, '$name')")
                    st.executeUpdate(
                        "INSERT INTO Cards(GrpId, TitleId, ExpansionCode, IsPrimaryCard) " +
                            "VALUES ($grpId, $titleId, '$expansion', $isPrimary)",
                    )
                }
            }
        }

        fun withDb(block: (SqliteCardRepository, String) -> Unit) {
            val dbFile = File.createTempFile("cardlookup", ".sqlite").apply { deleteOnExit() }
            val url = "jdbc:sqlite:${dbFile.absolutePath}"
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("CREATE TABLE Localizations_enUS(LocId INT, Formatted INT, Loc TEXT);")
                    st.executeUpdate(
                        """
                        CREATE TABLE Cards(
                          GrpId INT PRIMARY KEY, TitleId INT,
                          Power TEXT DEFAULT '', Toughness TEXT DEFAULT '',
                          Colors TEXT DEFAULT '', Types TEXT DEFAULT '',
                          Subtypes TEXT DEFAULT '', Supertypes TEXT DEFAULT '',
                          AbilityIds TEXT DEFAULT '', HiddenAbilityIds TEXT DEFAULT '',
                          OldSchoolManaText TEXT DEFAULT '',
                          AbilityIdToLinkedTokenGrpId TEXT DEFAULT '',
                          IsToken INT DEFAULT 0, IsPrimaryCard INT DEFAULT 1,
                          IsDigitalOnly INT DEFAULT 0, IsRebalanced INT DEFAULT 0,
                          ExpansionCode TEXT DEFAULT '', LinkedFaceType INTEGER DEFAULT 0,
                          LinkedFaceGrpIds TEXT DEFAULT ''
                        );
                        """.trimIndent(),
                    )
                }
            }
            block(SqliteCardRepository(Database.connect(url, "org.sqlite.JDBC")), url)
        }

        test("name + set resolves a non-primary-only printing (Universes Within)") {
            withDb { repo, url ->
                insertCard(url, grpId = 104694, titleId = 1, name = "Detect Intrusion", expansion = "OM1", isPrimary = 0)

                repo.findGrpIdByNameAndSet("Detect Intrusion", "OM1") shouldBe 104694
            }
        }

        test("name + set prefers the primary printing when both exist in the set") {
            withDb { repo, url ->
                insertCard(url, grpId = 200, titleId = 1, name = "Dual Print", expansion = "ABC", isPrimary = 0)
                insertCard(url, grpId = 201, titleId = 2, name = "Dual Print", expansion = "ABC", isPrimary = 1)

                repo.findGrpIdByNameAndSet("Dual Print", "ABC") shouldBe 201
            }
        }

        test("name + set still returns null when the card is absent from the set") {
            withDb { repo, url ->
                insertCard(url, grpId = 300, titleId = 1, name = "Detect Intrusion", expansion = "OM1", isPrimary = 0)

                repo.findGrpIdByNameAndSet("Detect Intrusion", "ZZZ") shouldBe null
            }
        }

        test("resolved grpId round-trips back to its name") {
            withDb { repo, url ->
                insertCard(url, grpId = 104694, titleId = 1, name = "Detect Intrusion", expansion = "OM1", isPrimary = 0)

                val grpId = repo.findGrpIdByNameAndSet("Detect Intrusion", "OM1")
                grpId.shouldNotBeNull()
                repo.findNameByGrpId(grpId) shouldBe "Detect Intrusion"
            }
        }

        test("aftermath titles normalize for Forge and round-trip through the name cache") {
            withDb { repo, url ->
                insertCard(url, grpId = 401, titleId = 1, name = "<nobr>Morning /// Evening</nobr>", expansion = "SYN", isPrimary = 1)

                repo.findNameByGrpId(401) shouldBe "Morning // Evening"
                repo.findGrpIdByName("Morning // Evening") shouldBe 401
            }
        }

        test("Forge aftermath names resolve through every uncached SQL lookup") {
            withDb { _, url ->
                insertCard(url, grpId = 402, titleId = 1, name = "<nobr>Morning /// Evening</nobr>", expansion = "SYN", isPrimary = 1)

                fun fresh() = SqliteCardRepository(Database.connect(url, "org.sqlite.JDBC"))

                assertSoftly {
                    fresh().findGrpIdByName("Morning // Evening") shouldBe 402
                    fresh().findGrpIdByNameAnyFace("Morning // Evening") shouldBe 402
                    fresh().findGrpIdByNameAndSet("Morning // Evening", "SYN") shouldBe 402
                }
            }
        }

        test("reverse lookup using an Arena aftermath name still caches the Forge name") {
            withDb { repo, url ->
                insertCard(url, grpId = 403, titleId = 1, name = "Morning /// Evening", expansion = "SYN", isPrimary = 1)

                assertSoftly {
                    repo.findGrpIdByName("Morning /// Evening") shouldBe 403
                    repo.findNameByGrpId(403) shouldBe "Morning // Evening"
                    repo.findGrpIdByName("Morning // Evening") shouldBe 403
                }
            }
        }

        test("ordinary names and existing split separators remain unchanged") {
            withDb { repo, url ->
                val names = listOf("Plain Name", "Left // Right", "Literal///Name")
                names.forEachIndexed { index, name ->
                    val grpId = 500 + index
                    insertCard(url, grpId, titleId = index + 1, name, expansion = "SYN", isPrimary = 1)

                    repo.findNameByGrpId(grpId) shouldBe name
                    repo.findGrpIdByName(name) shouldBe grpId
                }
            }
        }
    })
