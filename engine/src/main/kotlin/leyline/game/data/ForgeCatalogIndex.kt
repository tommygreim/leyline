package leyline.game.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Build-generated identities, independent of which Forge definitions are materialized. */
@Serializable
internal data class ForgeCatalogDescriptor(
    val indexes: Map<String, Int>,
    val identityIds: Map<String, Int>,
    val faceAliases: Map<String, List<FaceAlias>>,
    val version: String,
)

@Serializable
internal data class FaceAlias(
    val name: String,
    val parentName: String,
    val identityKey: String,
    val canonicalizeToParent: Boolean,
)

internal object ForgeCatalogIndex {
    const val IDENTITY_SCHEME = "forge-card-catalog-v7-granted-abilities"

    @Serializable
    private data class ArchiveIndex(
        val scheme: String,
        val resourceSha256: String,
        val descriptor: ForgeCatalogDescriptor,
    )

    fun write(
        path: Path,
        resourceSha256: String,
        descriptor: ForgeCatalogDescriptor,
    ) {
        require(resourceSha256.matches(Regex("[0-9a-f]{64}"))) { "Expected resource archive SHA-256" }
        Files.writeString(path, Json.encodeToString(ArchiveIndex.serializer(), ArchiveIndex(IDENTITY_SCHEME, resourceSha256, descriptor)))
    }

    fun read(
        path: Path,
        resourceSha256: String,
    ): ForgeCatalogDescriptor {
        val index = Json.decodeFromString(ArchiveIndex.serializer(), Files.readString(path))
        require(index.scheme == IDENTITY_SCHEME) { "Unsupported card identity scheme" }
        require(index.resourceSha256 == resourceSha256) { "Card index does not match the resource archive" }
        return index.descriptor
    }
}
