package leyline.game.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import org.jetbrains.exposed.v1.jdbc.Database
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.io.File
import java.sql.DriverManager

class SqliteCardRepositoryAbilityLocalizationTest :
    FunSpec({
        tags(UnitTag)

        fun withRepository(block: (SqliteCardRepository, String) -> Unit) {
            val dbFile = File.createTempFile("ability-localization", ".sqlite").apply { deleteOnExit() }
            val url = "jdbc:sqlite:${dbFile.absolutePath}"
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("CREATE TABLE Localizations_enUS(LocId INT, Formatted INT, Loc TEXT);")
                    statement.executeUpdate(
                        """
                        CREATE TABLE Abilities(
                          Id INT PRIMARY KEY, BaseId INT DEFAULT 0, TextId INT DEFAULT 0,
                          OldSchoolManaText TEXT, HiddenAbilityIds TEXT, ModalChildIds TEXT,
                          Category INT DEFAULT 0, SubCategory INT DEFAULT 0
                        );
                        """.trimIndent(),
                    )
                }
            }
            block(SqliteCardRepository(Database.connect(url, "org.sqlite.JDBC")), url)
        }

        test("ability localization selects formatted text and resolves mana cost") {
            withRepository { repository, url ->
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("INSERT INTO Localizations_enUS VALUES (21, 0, 'Internal template');")
                        statement.executeUpdate("INSERT INTO Localizations_enUS VALUES (21, 1, 'Choose one');")
                        statement.executeUpdate("INSERT INTO Abilities VALUES (7, 0, 21, 'o2oU', NULL, NULL, 8, 0);")
                    }
                }

                repository.findAbilityLocalization(7) shouldBe
                    AbilityLocalization(
                        text = "Choose one",
                        manaCost = listOf(ManaColor.Generic to 2, ManaColor.Blue_afc9 to 1),
                    )
            }
        }

        test("ability localization returns null when either row is missing") {
            withRepository { repository, url ->
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("INSERT INTO Abilities VALUES (7, 0, 21, NULL, NULL, NULL, 8, 0);")
                    }
                }

                repository.findAbilityLocalization(7) shouldBe null
                repository.findAbilityLocalization(8) shouldBe null
            }
        }

        test("ability metadata retains hidden child rows") {
            withRepository { repository, url ->
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("INSERT INTO Abilities VALUES (7, 0, 21, NULL, '8,9', NULL, 2, 0);")
                    }
                }

                repository.findAbilityInfo(7)?.hiddenAbilityIds shouldBe listOf(8, 9)
            }
        }
    })
