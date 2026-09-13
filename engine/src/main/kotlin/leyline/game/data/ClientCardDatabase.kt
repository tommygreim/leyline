package leyline.game.data

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Owns discovery, validation, connection, and [CardRepository] construction
 * for the client card database — the single resolution policy shared by
 * every runtime consumer (native, embedding hosts, seed-db, standalone simclient, and
 * the operator lookup recipes).
 *
 * Resolution policy:
 *  1. `LEYLINE_CARD_DB`, when set, is an authoritative override. An invalid
 *     override fails without falling back to discovery.
 *  2. Otherwise the newest *usable* database under `LEYLINE_ARENA_DOWNLOADS`
 *     or the standard client installation location is selected, and its filename is
 *     reported so diagnostics can show which database a run is using.
 *
 * *Usable* means the file exists, is larger than a placeholder, opens as
 * SQLite, and a card query returns rows. Test and harness consumers keep
 * their YAML-fixture repositories — this class exists only for the client
 * database, and never falls back to synthetic or fixture data.
 */
class ClientCardDatabase private constructor(
    /** Resolved, validated client database file. */
    val path: File,
) {
    /** Lazily-connected Exposed handle over [path]. */
    private val database: Database = Database.connect("jdbc:sqlite:${path.absolutePath}", "org.sqlite.JDBC")

    /** A read-only [CardRepository] over the validated database. */
    fun cardRepository(): CardRepository = SqliteCardRepository(database)

    companion object {
        private val log = LoggerFactory.getLogger(ClientCardDatabase::class.java)
        private const val MIN_CARD_DB_BYTES = 1_000_000L

        /** Open the client card database with an optional explicit override; otherwise standard-location autodiscovery. */
        fun open(overridePath: String? = null): ClientCardDatabase =
            open(overridePath = overridePath, standardLocation = ::detectArenaDownloadsDir)

        /**
         * Resolve and validate the client card database path without holding a
         * connection. Thin operator tooling (the just lookup recipes) uses this
         * to obtain one validated path for direct SQL.
         */
        fun resolveValidatedPath(overridePath: String? = null): File =
            resolveValidatedPath(overridePath = overridePath, standardLocation = ::detectArenaDownloadsDir)

        internal fun open(
            overridePath: String?,
            standardLocation: () -> File?,
        ): ClientCardDatabase {
            val path = resolveValidatedPath(overridePath, standardLocation)
            log
                .atInfo()
                .addKeyValue("event", "card_database.opened")
                .addKeyValue("filename", path.name)
                .log("Client card database opened")
            return ClientCardDatabase(path)
        }

        internal fun resolveValidatedPath(
            overridePath: String?,
            standardLocation: () -> File?,
        ): File {
            val explicit = overridePath?.takeIf { it.isNotBlank() }
            if (explicit != null) {
                // Authoritative override — an invalid override fails hard, never falls back.
                return validateUsable(File(explicit))
            }
            discoverCandidates(standardLocation)
                .firstNotNullOfOrNull { validateUsableOrNull(it) }
                ?.let { return it }
            error(
                "Card database not found. Set LEYLINE_CARD_DB or LEYLINE_ARENA_DOWNLOADS, or install the compatible client.\n" +
                    "  macOS: ~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga\n" +
                    "  Windows: C:/Program Files/Epic Games/MagicTheGathering/MTGA_Data/Downloads/Raw/Raw_CardDatabase_*.mtga\n" +
                    "  Linux (Steam/Proton): ~/.local/share/Steam/steamapps/common/MTGA/MTGA_Data/Downloads/Raw/Raw_CardDatabase_*.mtga",
            )
        }

        private fun discoverCandidates(standardLocation: () -> File?): List<File> {
            val rawDir = standardLocation()?.resolve("Raw") ?: return emptyList()
            if (!rawDir.isDirectory) return emptyList()
            return rawDir
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.name.startsWith("Raw_CardDatabase_") }
                .filter { it.name.endsWith(".mtga") || it.name.endsWith(".sqlite") }
                .filter { it.length() >= MIN_CARD_DB_BYTES }
                .sortedByDescending { it.lastModified() }
                .toList()
        }

        private fun validateUsableOrNull(file: File): File? = runCatching { validateUsable(file) }.getOrNull()

        private fun validateUsable(file: File): File {
            require(file.exists()) { "Card database not found at: ${file.path}" }
            require(file.length() >= MIN_CARD_DB_BYTES) {
                "Card database at ${file.path} is ${file.length()} bytes — too small to be a real DB.\n" +
                    "Likely an in-progress download placeholder alongside a real file in the same directory.\n" +
                    "Remove the empty file and rerun, or set LEYLINE_CARD_DB to the full DB explicitly."
            }
            val database = Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC")
            try {
                transaction(database) { exec("SELECT 1") { it.next() } }
            } catch (e: Exception) {
                error("Card database at ${file.path} does not open as SQLite: ${e.message}")
            }
            val grpIds = SqliteCardRepository(database).findAllGrpIds()
            check(grpIds.isNotEmpty()) {
                "Card database at ${file.path} has no usable Cards rows. Wrong file, or schema changed."
            }
            return file
        }

        /**
         * Locate the local client Downloads directory across platforms.
         *
         * `LEYLINE_ARENA_DOWNLOADS` overrides discovery for both card data and
         * cached manifests. An invalid override fails instead of silently using
         * assets from a different client installation.
         *
         * macOS: ~/Library/Application Support/com.wizards.mtga/Downloads
         * Windows: <Epic install>/MTGA_Data/Downloads (card data lives inside the install)
         * Linux: native or Flatpak Steam library (the client runs through Proton)
         */
        fun detectArenaDownloadsDir(): File? =
            detectArenaDownloadsDir(
                environment = System.getenv(),
                osName = System.getProperty("os.name"),
                home = File(System.getProperty("user.home")),
            )

        internal fun detectArenaDownloadsDir(
            environment: Map<String, String>,
            osName: String,
            home: File,
        ): File? {
            environment["LEYLINE_ARENA_DOWNLOADS"]?.takeIf { it.isNotBlank() }?.let { path ->
                val dir = File(path)
                require(dir.isDirectory) { "LEYLINE_ARENA_DOWNLOADS is not a directory: $path" }
                return dir
            }

            val os = osName.lowercase()

            // macOS: user-local application support
            if (os.contains("mac")) {
                val dir = home.resolve("Library/Application Support/com.wizards.mtga/Downloads")
                if (dir.isDirectory) return dir
            }

            // Windows: inside Epic Games or Steam install directory
            if (os.contains("win")) {
                val programFiles = environment["PROGRAMFILES"] ?: "C:/Program Files"
                val programFilesX86 = environment["PROGRAMFILES(X86)"] ?: "C:/Program Files (x86)"
                val candidates =
                    listOf(
                        File(programFiles, "Epic Games/MagicTheGathering/MTGA_Data/Downloads"),
                        File(programFilesX86, "Epic Games/MagicTheGathering/MTGA_Data/Downloads"),
                        File(programFilesX86, "Steam/steamapps/common/MTGA/MTGA_Data/Downloads"),
                    )
                candidates.firstOrNull { it.isDirectory }?.let { return it }
            }

            if (os.contains("linux")) {
                val steamRoots =
                    listOf(
                        ".local/share/Steam",
                        ".steam/steam",
                        ".steam/root",
                        ".var/app/com.valvesoftware.Steam/.local/share/Steam",
                    )
                steamRoots
                    .map { home.resolve("$it/steamapps/common/MTGA/MTGA_Data/Downloads") }
                    .firstOrNull { it.isDirectory }
                    ?.let { return it }
            }

            return null
        }
    }
}
