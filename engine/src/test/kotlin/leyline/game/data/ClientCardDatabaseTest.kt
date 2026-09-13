package leyline.game.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/**
 * Covers the single client-database resolution policy: authoritative override,
 * standard-location autodiscovery (newest usable wins), and the strict
 * usability validation (exists, opens as SQLite, card rows present).
 */
class ClientCardDatabaseTest :
    FunSpec({

        tags(UnitTag)

        fun tempDir(): File = Files.createTempDirectory("leyline-card-db").toFile()

        /** Append bytes so the file clears the placeholder-size threshold without breaking SQLite reads. */
        fun padToMinDbSize(file: File) {
            val pad = (1_000_000L - file.length()).coerceAtLeast(0)
            if (pad > 0) file.appendBytes(ByteArray(pad.toInt()))
        }

        /** Create a SQLite file shaped like the client Cards schema with one usable row. */
        fun createUsableDb(file: File) {
            file.parentFile?.mkdirs()
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
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
                    st.execute("INSERT INTO Cards(GrpId, TitleId) VALUES (1, 1)")
                }
            }
            padToMinDbSize(file)
        }

        fun rawDirWith(
            dir: File,
            vararg dbs: Pair<String, File>,
        ): File {
            val raw = File(dir, "Raw").apply { mkdirs() }
            for ((name, source) in dbs) {
                val target = File(raw, name)
                source.copyTo(target, overwrite = true)
            }
            return raw
        }

        test("Downloads override selects card data outside the default Steam library") {
            val home = tempDir()
            val defaultDownloads = home.resolve(".local/share/Steam/steamapps/common/MTGA/MTGA_Data/Downloads")
            createUsableDb(defaultDownloads.resolve("Raw/Raw_CardDatabase_default.mtga"))
            val customDownloads = home.resolve("custom-library/MTGA_Data/Downloads")
            val customDb = customDownloads.resolve("Raw/Raw_CardDatabase_custom.mtga")
            createUsableDb(customDb)
            val environment = mapOf("LEYLINE_ARENA_DOWNLOADS" to customDownloads.absolutePath)

            val downloads = ClientCardDatabase.detectArenaDownloadsDir(environment, "Linux", home)
            val db = ClientCardDatabase.resolveValidatedPath(overridePath = null, standardLocation = { downloads })

            downloads shouldBe customDownloads
            db shouldBe customDb
        }

        test("invalid Downloads override fails even when the standard library exists") {
            val home = tempDir()
            home.resolve(".local/share/Steam/steamapps/common/MTGA/MTGA_Data/Downloads").mkdirs()

            val thrown =
                shouldThrow<IllegalArgumentException> {
                    ClientCardDatabase.detectArenaDownloadsDir(
                        environment = mapOf("LEYLINE_ARENA_DOWNLOADS" to home.resolve("missing").absolutePath),
                        osName = "Linux",
                        home = home,
                    )
                }

            thrown.message shouldContain "LEYLINE_ARENA_DOWNLOADS is not a directory"
        }

        test("explicit card database does not consult Downloads discovery") {
            val home = tempDir()
            val db = home.resolve("override.sqlite")
            createUsableDb(db)

            val resolved =
                ClientCardDatabase.resolveValidatedPath(
                    overridePath = db.absolutePath,
                    standardLocation = { error("Downloads discovery must not run for an explicit card database") },
                )

            resolved shouldBe db
        }

        listOf(
            ".local/share/Steam",
            ".steam/steam",
            ".steam/root",
            ".var/app/com.valvesoftware.Steam/.local/share/Steam",
        ).forEach { steamRoot ->
            test("Linux discovers the Steam library under $steamRoot") {
                val home = tempDir()
                val downloads = home.resolve("$steamRoot/steamapps/common/MTGA/MTGA_Data/Downloads").apply { mkdirs() }

                ClientCardDatabase.detectArenaDownloadsDir(emptyMap(), "Linux", home) shouldBe downloads
            }
        }

        test("blank Downloads override keeps macOS discovery") {
            val home = tempDir()
            val downloads = home.resolve("Library/Application Support/com.wizards.mtga/Downloads").apply { mkdirs() }

            ClientCardDatabase.detectArenaDownloadsDir(
                environment = mapOf("LEYLINE_ARENA_DOWNLOADS" to " "),
                osName = "Mac OS X",
                home = home,
            ) shouldBe downloads
        }

        listOf(
            "PROGRAMFILES" to "Epic Games/MagicTheGathering/MTGA_Data/Downloads",
            "PROGRAMFILES(X86)" to "Epic Games/MagicTheGathering/MTGA_Data/Downloads",
            "PROGRAMFILES(X86)" to "Steam/steamapps/common/MTGA/MTGA_Data/Downloads",
        ).forEach { (variable, relativePath) ->
            test("Windows discovers $relativePath under $variable") {
                val home = tempDir()
                val environment =
                    mapOf(
                        "PROGRAMFILES" to home.resolve("program-files").absolutePath,
                        "PROGRAMFILES(X86)" to home.resolve("program-files-x86").absolutePath,
                    )
                val downloads = File(environment.getValue(variable), relativePath).apply { mkdirs() }

                ClientCardDatabase.detectArenaDownloadsDir(environment, "Windows 11", home) shouldBe downloads
            }
        }

        test("missing Linux installation leaves Downloads unresolved") {
            ClientCardDatabase.detectArenaDownloadsDir(emptyMap(), "Linux", tempDir()) shouldBe null
        }

        test("explicit override opens a usable database") {
            val dir = tempDir()
            val db = File(dir, "override.sqlite")
            createUsableDb(db)

            val opened = ClientCardDatabase.open(overridePath = db.absolutePath, standardLocation = { null })

            opened.path shouldBe db
            opened.cardRepository().findAllGrpIds() shouldBe listOf(1)
        }

        test("invalid explicit override fails without fallback to standard location") {
            val dir = tempDir()
            val standard = File(dir, "standard.sqlite")
            createUsableDb(standard)
            val raw = rawDirWith(dir, "Raw_CardDatabase_standard.mtga" to standard)

            val thrown =
                shouldThrow<IllegalArgumentException> {
                    ClientCardDatabase.open(overridePath = File(dir, "missing.sqlite").absolutePath, standardLocation = { raw.parentFile })
                }

            thrown.message shouldContain "Card database not found at"
        }

        test("missing standard database fails with installation instructions") {
            val dir = tempDir()

            val thrown =
                shouldThrow<IllegalStateException> {
                    ClientCardDatabase.resolveValidatedPath(overridePath = null, standardLocation = { dir })
                }

            thrown.message shouldContain "Card database not found"
            thrown.message shouldContain "LEYLINE_CARD_DB"
        }

        test("autodiscovery selects the newest usable candidate") {
            val dir = tempDir()
            val older = File(dir, "older.sqlite")
            val newer = File(dir, "newer.sqlite")
            createUsableDb(older)
            createUsableDb(newer)
            rawDirWith(dir, "Raw_CardDatabase_older.mtga" to older)
            val raw = rawDirWith(dir, "Raw_CardDatabase_newer.mtga" to newer)
            // Ensure a stable newest-first ordering regardless of filesystem mtimes.
            File(raw, "Raw_CardDatabase_newer.mtga").setLastModified(System.currentTimeMillis() + 60_000)
            File(raw, "Raw_CardDatabase_older.mtga").setLastModified(System.currentTimeMillis() - 60_000)

            val path = ClientCardDatabase.resolveValidatedPath(overridePath = null, standardLocation = { raw.parentFile })

            path.name shouldBe "Raw_CardDatabase_newer.mtga"
        }

        test("autodiscovery skips invalid candidates and uses a usable older one") {
            val dir = tempDir()
            val usable = File(dir, "usable.sqlite")
            createUsableDb(usable)
            val corrupt =
                File(dir, "corrupt.sqlite").apply {
                    writeBytes(ByteArray(2_000_000) { 0x42 })
                }
            val raw = rawDirWith(dir, "Raw_CardDatabase_corrupt.mtga" to corrupt)
            rawDirWith(dir, "Raw_CardDatabase_usable.mtga" to usable)
            File(raw, "Raw_CardDatabase_corrupt.mtga").setLastModified(System.currentTimeMillis() + 60_000)
            File(raw, "Raw_CardDatabase_usable.mtga").setLastModified(System.currentTimeMillis() - 60_000)

            val path = ClientCardDatabase.resolveValidatedPath(overridePath = null, standardLocation = { raw.parentFile })

            path.name shouldBe "Raw_CardDatabase_usable.mtga"
        }

        test("placeholder-sized candidate is rejected as too small") {
            val dir = tempDir()
            val placeholder = File(dir, "placeholder.sqlite").apply { writeText("placeholder") }
            rawDirWith(dir, "Raw_CardDatabase_placeholder.mtga" to placeholder)

            val thrown =
                shouldThrow<IllegalStateException> {
                    ClientCardDatabase.resolveValidatedPath(overridePath = null, standardLocation = { dir })
                }

            thrown.message shouldContain "Card database not found"
        }

        test("explicit override rejects a placeholder-sized file as too small") {
            val dir = tempDir()
            val placeholder = File(dir, "placeholder.sqlite").apply { writeText("placeholder") }

            val thrown =
                shouldThrow<IllegalArgumentException> {
                    ClientCardDatabase.resolveValidatedPath(overridePath = placeholder.absolutePath, standardLocation = { null })
                }

            thrown.message shouldContain "too small to be a real DB"
        }

        test("explicit override rejects a non-SQLite file") {
            val dir = tempDir()
            val garbage = File(dir, "garbage.sqlite").apply { writeBytes(ByteArray(2_000_000) { 0x42 }) }

            val thrown =
                shouldThrow<IllegalStateException> {
                    ClientCardDatabase.resolveValidatedPath(overridePath = garbage.absolutePath, standardLocation = { null })
                }

            thrown.message shouldContain "does not open as SQLite"
        }

        test("explicit override rejects a SQLite file with no usable card rows") {
            val dir = tempDir()
            val empty = File(dir, "empty.sqlite")
            DriverManager.getConnection("jdbc:sqlite:${empty.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
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
            padToMinDbSize(empty)

            val thrown =
                shouldThrow<IllegalStateException> {
                    ClientCardDatabase.resolveValidatedPath(overridePath = empty.absolutePath, standardLocation = { null })
                }

            thrown.message shouldContain "no usable Cards rows"
        }
    })
