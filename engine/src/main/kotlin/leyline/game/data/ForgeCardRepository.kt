package leyline.game.data

import forge.StaticData
import forge.card.CardRules
import forge.card.ICardFace
import forge.localinstance.properties.ForgeConstants
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ManaColorMapping
import leyline.bridge.types.manaTokenToPair
import leyline.game.InMemoryCardRepository
import leyline.game.codes.SlotKind
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.SuperType
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.HexFormat

/** Forge-backed card metadata with catalog-scoped GRE identities. */
class ForgeCardRepository private constructor(
    private val cardIndexByName: Map<String, Int>,
    internal val catalogIdentityIds: Map<String, Int>,
    private val faceAliases: Map<String, List<FaceAlias>>,
    val catalogVersion: String,
) : CardRepository {
    private val rows = InMemoryCardRepository()
    private val loading = mutableSetOf<String>()
    private val tokens = mutableMapOf<String, MutableSet<Int>>()
    private val primaryIds = cardIndexByName.values.map { CARD_BASE + it }.toSet()
    private val primaryNameById = cardIndexByName.entries.associate { (name, index) -> CARD_BASE + index to name }
    private val primaryNamesByLookup = cardIndexByName.keys.groupBy(::lookupKey)
    private val faceAliasesByParent = faceAliases.values.flatten().groupBy(FaceAlias::parentName)
    private val faceAliasesByLookup = faceAliases.values.flatten().groupBy { lookupKey(it.name) }
    private val combinedNamesByParent =
        faceAliasesByParent.mapValues { (parentName, aliases) ->
            if (aliases.size == 1 && " // " !in parentName) "$parentName // ${aliases.single().name}" else null
        }
    private val combinedParentsByLookup =
        combinedNamesByParent.entries
            .mapNotNull { (parentName, combinedName) -> combinedName?.let { lookupKey(it) to parentName } }
            .groupBy({ it.first }, { it.second })
    private val faceAliasById =
        faceAliases.values.flatten().associateBy { alias -> catalogIdentityIds.getValue(alias.identityKey) }
    internal val identityKeys = primaryNameById.mapValuesTo(linkedMapOf()) { (_, name) -> "card:$name" }
    private val keywordBases =
        KeywordAbilityIds::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { normalize(it.name) to it.getInt(null) }

    companion object {
        private const val CARD_BASE = 200_000_000
        private const val DERIVED_ID_BASE = 300_000_000
        private val descriptor: ForgeCatalogDescriptor by lazy {
            GameBootstrap.initializeCardDatabase(quiet = true)
            StaticData.instance().ensureAllCardsLoaded()
            val names =
                StaticData
                    .instance()
                    .commonCards.uniqueCards
                    .map { it.name }
                    .distinct()
                    .sorted()
            val tokenScripts =
                Files
                    .list(Paths.get(ForgeConstants.TOKEN_DATA_DIR))
                    .use { paths ->
                        paths
                            .filter(Files::isRegularFile)
                            .map { it.fileName.toString() }
                            .filter { it.endsWith(".txt") }
                            .map { it.removeSuffix(".txt") }
                            .sorted()
                            .toList()
                    }
            val keys =
                (names.flatMap(::definitionKeys) + tokenScripts.flatMap(::tokenDefinitionKeys))
                    .distinct()
                    .sorted()
            val identityIds = keys.withIndex().associate { it.value to DERIVED_ID_BASE + it.index }
            val aliases = names.flatMap(::faceAliases)
            require(DERIVED_ID_BASE.toLong() + keys.size <= Int.MAX_VALUE) { "Card catalog exceeds the GRE identity range" }
            ForgeCatalogDescriptor(
                names.withIndex().associate { it.value to it.index },
                identityIds,
                aliases.groupBy { it.name },
                definitionVersion(names),
            )
        }

        /** Materializes the complete catalog before deriving its identity set. */
        fun open(): ForgeCardRepository = create(descriptor)

        /** Reads a generated identity index after the host initializes its Forge catalog. */
        fun open(
            indexPath: Path,
            resourceSha256: String,
        ): ForgeCardRepository = create(ForgeCatalogIndex.read(indexPath, resourceSha256))

        /** Export from an eagerly loaded catalog using the exact packaged resource hash. */
        fun writeCatalogIndex(
            path: Path,
            resourceSha256: String,
        ) {
            ForgeCatalogIndex.write(path, resourceSha256, descriptor)
        }

        private fun create(descriptor: ForgeCatalogDescriptor): ForgeCardRepository =
            ForgeCardRepository(
                descriptor.indexes,
                descriptor.identityIds,
                descriptor.faceAliases,
                descriptor.version,
            )

        private fun faceAliases(name: String): List<FaceAlias> {
            val rules = requireNotNull(StaticData.instance().commonCards.getCard(name)).rules
            return rules.allFaces.mapIndexedNotNull { index, face ->
                if (face.name == rules.name) {
                    null
                } else {
                    FaceAlias(
                        face.name,
                        rules.name,
                        faceIdentityKey(rules, index),
                        canonicalizeToParent = rules.splitType.name in setOf("Split", "Specialize"),
                    )
                }
            }
        }

        private fun definitionKeys(name: String): List<String> {
            val rules = requireNotNull(StaticData.instance().commonCards.getCard(name)).rules
            return buildList {
                rules.allFaces.forEachIndexed { faceIndex, face ->
                    if (faceIndex > 0 || rules.splitType.name == "Split") add(faceIdentityKey(rules, faceIndex))
                    addFaceKeys(face, "${rules.name}:face:$faceIndex")
                }
                rules.tokens.distinct().forEachIndexed { tokenIndex, script ->
                    val token = StaticData.instance().allTokens.getToken(script) ?: return@forEachIndexed
                    add("token:${rules.name}:$tokenIndex:$script")
                    addFaceKeys(token.rules.mainPart, "${rules.name}:token:$tokenIndex:$script")
                    add("${rules.name}:token-source:$tokenIndex:$script")
                }
            }
        }

        private fun tokenDefinitionKeys(script: String): List<String> {
            val token = StaticData.instance().allTokens.getToken(script) ?: return emptyList()
            return buildList {
                add("token-script:$script")
                addFaceKeys(token.rules.mainPart, "token-script:$script")
            }
        }

        private fun faceIdentityKey(
            rules: CardRules,
            index: Int,
        ): String = "face:${rules.name}:$index:${rules.allFaces[index].name}"

        private fun MutableList<String>.addFaceKeys(
            face: ICardFace,
            prefix: String,
        ) {
            var slot = 0

            fun addRows(rows: Iterable<String>) {
                rows.forEach { raw ->
                    add("$prefix:ability:${slot++}:$raw")
                    val variables = face.variables.associate { it.key to it.value }
                    val parsed = parseParams(raw)
                    val effect = parsed["Execute"]?.let(variables::get)?.let(::parseParams) ?: parsed
                    effect["Choices"]?.split(',')?.forEachIndexed { index, variable ->
                        add("$prefix:mode:${slot - 1}:$index:$variable")
                    }
                }
            }
            addRows(face.keywords)
            addRows(face.abilities)
            addRows(face.triggers)
            addRows(face.staticAbilities)
            addRows(face.replacements)
        }

        private fun definitionVersion(names: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")

            fun frame(value: ByteArray) {
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
                digest.update(value)
            }
            frame(ForgeCatalogIndex.IDENTITY_SCHEME.toByteArray())
            names.forEach { frame(it.toByteArray()) }
            listOf(ForgeConstants.CARD_DATA_DIR, ForgeConstants.TOKEN_DATA_DIR)
                .map(Paths::get)
                .forEach { root ->
                    require(Files.isDirectory(root)) { "Forge definition directory is unavailable: $root" }
                    frame(root.fileName.toString().toByteArray())
                    Files.walk(root).use { paths ->
                        paths.filter(Files::isRegularFile).sorted().forEach { path ->
                            frame(root.relativize(path).toString().toByteArray())
                            frame(Files.readAllBytes(path))
                        }
                    }
                }
            return HexFormat.of().formatHex(digest.digest())
        }
    }

    @Synchronized
    override fun findGrpIdByName(name: String): Int? {
        val catalogIndex = cardIndexByName[name]
        if (catalogIndex == null) {
            val alias = faceAliases[name]?.singleOrNull() ?: return null
            val parentId = findGrpIdByName(alias.parentName) ?: return null
            if (alias.canonicalizeToParent) return parentId
            return catalogIdentityIds[alias.identityKey]
        }
        rows.findByGrpId(CARD_BASE + catalogIndex)?.let { return it.grpId }
        if (!loading.add(name)) return null
        try {
            val data = StaticData.instance()
            val paper =
                data.commonCards.getCard(name) ?: run {
                    data.attemptToLoadCard(name)
                    data.commonCards.getCard(name)
                } ?: return null
            registerRules(paper.rules, cardIndexByName[paper.rules.name] ?: catalogIndex)
            return rows.findGrpIdByName(name)
        } finally {
            loading.remove(name)
        }
    }

    @Synchronized
    override fun findDeckGrpIdByName(name: String): Int? {
        val key = lookupKey(name)
        val parentName =
            primaryNamesByLookup[key]?.singleOrNull()
                ?: faceAliasesByLookup[key]?.map(FaceAlias::parentName)?.distinct()?.singleOrNull()
                ?: combinedParentsByLookup[key]?.distinct()?.singleOrNull()
                ?: return null
        return findGrpIdByName(parentName)
    }

    override fun findDeckGrpIdByNameAndSet(
        name: String,
        setCode: String,
    ): Int? = findDeckGrpIdByName(name)

    @Synchronized
    override fun findNamesByGrpId(grpId: Int): List<String> {
        val parentName = primaryNameById[grpId] ?: return super.findNamesByGrpId(grpId)
        return buildList {
            add(parentName)
            addAll(faceAliasesByParent[parentName].orEmpty().map(FaceAlias::name))
            combinedNamesByParent[parentName]?.let(::add)
        }.distinct()
    }

    private fun registerRules(
        rules: CardRules,
        catalogIndex: Int,
    ) {
        val faces = rules.allFaces
        val parentId = CARD_BASE + catalogIndex
        val hasCombinedParent = rules.splitType.name == "Split"
        val faceIds =
            faces.indices.map {
                if (it == 0 && !hasCombinedParent) {
                    parentId
                } else {
                    identityId("face:${rules.name}:$it:${faces[it].name}")
                }
            }
        faces.forEachIndexed { index, face ->
            claim(
                faceIds[index],
                if (index == 0 && !hasCombinedParent) "card:${rules.name}" else "face:${rules.name}:$index:${face.name}",
            )
            val linkedType =
                if (rules.splitType.name == "Adventure") {
                    if (index == 0) {
                        8
                    } else {
                        7
                    }
                } else {
                    0
                }
            registerFace(face, faceIds[index], faceIds.filter { it != faceIds[index] }, linkedType, "${rules.name}:face:$index")
        }
        if (hasCombinedParent) registerCombinedParent(rules, parentId, faceIds)
        rows.register(parentId, rules.name)

        val tokenIds =
            rules.tokens
                .distinct()
                .mapIndexedNotNull { index, script ->
                    val token = StaticData.instance().allTokens.getToken(script) ?: return@mapIndexedNotNull null
                    val tokenId = identityId("token:${rules.name}:$index:$script")
                    registerFace(
                        token.rules.mainPart,
                        tokenId,
                        emptyList(),
                        0,
                        "${rules.name}:token:$index:$script",
                    )
                    val tokenName = token.rules.mainPart.name
                    tokens.getOrPut(tokenName) { mutableSetOf() }.add(tokenId)
                    tokens.getOrPut(tokenName.removeSuffix(" Token")) { mutableSetOf() }.add(tokenId)
                    identityId("${rules.name}:token-source:$index:$script") to tokenId
                }.toMap()
        val namedIds =
            buildList {
                add(parentId to rules.name)
                faces.forEachIndexed { index, face -> add(faceIds[index] to face.name) }
            }.distinctBy { it.first }
        namedIds.forEach { (id, name) ->
            val row = requireNotNull(rows.findByGrpId(id))
            rows.registerData(row.copy(tokenGrpIds = tokenIds), name)
        }
        rows.register(parentId, rules.name)
    }

    private fun registerCombinedParent(
        rules: CardRules,
        parentId: Int,
        faceIds: List<Int>,
    ) {
        claim(parentId, "card:${rules.name}")
        val faceRows = faceIds.map { requireNotNull(rows.findByGrpId(it)) }
        val typeNames = rules.type.coreTypes.map { it.name }
        val subtypeNames = rules.type.subtypes.toList()
        val abilities = faceRows.flatMap { it.abilityIds }
        val kinds = faceRows.flatMap { it.abilityKinds }
        val categories = faceRows.flatMap { it.abilityCategories }
        rows.registerData(
            CardData(
                grpId = parentId,
                titleId = parentId,
                power = rules.power.orEmpty(),
                toughness = rules.toughness.orEmpty(),
                colors =
                    listOf(
                        1 to rules.color.hasWhite(),
                        2 to rules.color.hasBlue(),
                        3 to rules.color.hasBlack(),
                        4 to rules.color.hasRed(),
                        5 to rules.color.hasGreen(),
                    ).filter { it.second }.map { it.first },
                types = enums(typeNames, CardType.values().filter { it.name != "UNRECOGNIZED" }.map { it.name to it.number }),
                subtypes = enums(subtypeNames, SubType.values().filter { it.name != "UNRECOGNIZED" }.map { it.name to it.number }),
                supertypes =
                    enums(
                        rules.type.supertypes.map { it.name },
                        SuperType.values().filter { it.name != "UNRECOGNIZED" }.map { it.name to it.number },
                    ),
                abilityIds = abilities,
                abilityKinds = kinds,
                abilityCategories = categories,
                manaCost = ManaColorMapping.deriveManaCost(rules.manaCost),
                linkedFaceGrpIds = faceIds,
                typeNames = typeNames,
                subtypeNames = subtypeNames,
                keywordNames = faceRows.flatMap { it.keywordNames }.distinct(),
            ),
            rules.name,
        )
    }

    private fun registerFace(
        face: ICardFace,
        cardId: Int,
        linked: List<Int>,
        linkedType: Int,
        identityPrefix: String,
    ) {
        val abilities = mutableListOf<Pair<Int, Int>>()
        val kinds = mutableListOf<SlotKind>()
        val categories = mutableListOf<Int>()
        val variables = face.variables.associate { it.key to it.value }

        var semanticSlot = 0

        fun addRow(
            raw: String,
            category: Int,
            kind: SlotKind,
            base: Int = 0,
            cost: String = "",
        ): Int {
            val keySlot = semanticSlot++
            val id = identityId("$identityPrefix:ability:$keySlot:$raw")
            val mana = cost.split(Regex("\\s+")).mapNotNull(::manaTokenToPair)
            abilities += id to id
            kinds += kind
            categories += category
            rows.registerAbilityInfo(id, AbilityInfo(base, mana, category, if (kind == SlotKind.Mana) 1 else 0))
            val parsed = parseParams(raw)
            val text = parsed["SpellDescription"] ?: parsed["TriggerDescription"] ?: parsed["Description"] ?: raw
            rows.registerAbilityLocalization(id, AbilityLocalization(text, mana))
            val effect = parsed["Execute"]?.let(variables::get)?.let(::parseParams) ?: parsed
            effect["Choices"]
                ?.split(",")
                ?.mapIndexed { index, variable ->
                    val child = identityId("$identityPrefix:mode:$keySlot:$index:$variable")
                    val description = variables[variable]?.let(::parseParams)?.get("SpellDescription") ?: variable
                    rows.registerAbilityLocalization(child, AbilityLocalization(description))
                    child
                }?.let { rows.registerModalOptions(cardId, ModalAbilityInfo(id, it)) }
            return id
        }

        face.keywords.forEach { keyword ->
            val parts = keyword.split(":")
            addRow(keyword, 8, SlotKind.Keyword, keywordBases[normalize(parts.first())] ?: 0, parts.getOrElse(1) { "" })
        }
        face.abilities.forEach { raw ->
            val parsed = parseParams(raw)
            val activated = parsed.containsKey("AB")
            val mana = parsed["AB"] in listOf("Mana", "ManaReflected")
            addRow(
                raw,
                if (activated) 1 else 4,
                if (mana) {
                    SlotKind.Mana
                } else if (activated) {
                    SlotKind.Activated
                } else {
                    SlotKind.Intrinsic
                },
                cost = parsed["Cost"].orEmpty(),
            )
        }
        face.triggers.forEach { raw -> addRow(raw, 2, SlotKind.Intrinsic) }
        face.staticAbilities.forEach { raw -> addRow(raw, 3, SlotKind.Intrinsic) }
        face.replacements.forEach { raw -> addRow(raw, 3, SlotKind.Intrinsic) }
        if (face.type.isBasicLand) {
            BasicLandAbilities.byForgeSubtypeNames(face.type.subtypes)?.let { id ->
                abilities += id to id
                kinds += SlotKind.Mana
                categories += 1
            }
        }
        val colors =
            listOf(
                1 to face.color.hasWhite(),
                2 to face.color.hasBlue(),
                3 to face.color.hasBlack(),
                4 to face.color.hasRed(),
                5 to face.color.hasGreen(),
            ).filter { it.second }
                .map { it.first }
        val typeNames = face.type.coreTypes.map { it.name }
        val subtypeNames = face.type.subtypes.toList()
        rows.registerData(
            CardData(
                grpId = cardId,
                titleId = cardId,
                power = face.power.orEmpty(),
                toughness = face.toughness.orEmpty(),
                colors = colors,
                types = enums(typeNames, CardType.values().filter { it.name != "UNRECOGNIZED" }.map { it.name to it.number }),
                subtypes = enums(subtypeNames, SubType.values().filter { it.name != "UNRECOGNIZED" }.map { it.name to it.number }),
                supertypes =
                    enums(
                        face.type.supertypes.map { it.name },
                        SuperType.values().filter { it.name != "UNRECOGNIZED" }.map {
                            it.name to
                                it.number
                        },
                    ),
                abilityIds = abilities,
                abilityKinds = kinds,
                abilityCategories = categories,
                manaCost = ManaColorMapping.deriveManaCost(face.manaCost),
                linkedFaceType = linkedType,
                linkedFaceGrpIds = linked,
                typeNames = typeNames,
                subtypeNames = subtypeNames,
                keywordNames = face.keywords.map { it.substringBefore(':').replace('_', ' ') },
            ),
            face.name,
        )
    }

    private fun identityId(key: String): Int =
        requireNotNull(catalogIdentityIds[key]) {
            "Missing catalog identity: $key"
        }.also { claim(it, key) }

    private fun claim(
        id: Int,
        key: String,
    ) {
        val previous = identityKeys.putIfAbsent(id, key)
        check(previous == null || previous == key) { "Card identity collision: $previous and $key" }
    }

    private fun enums(
        names: List<String>,
        values: List<Pair<String, Int>>,
    ): List<Int> = names.mapNotNull { name -> values.firstOrNull { normalize(it.first.substringBefore('_')) == normalize(name) }?.second }

    @Synchronized
    override fun findByGrpId(grpId: Int): CardData? {
        rows.findByGrpId(grpId)?.let { return it }
        primaryNameById[grpId]?.let(::findGrpIdByName)
        faceAliasById[grpId]?.let { findGrpIdByName(it.parentName) }
        return rows.findByGrpId(grpId)
    }

    @Synchronized
    override fun findNameByGrpId(grpId: Int): String? = rows.findNameByGrpId(grpId) ?: primaryNameById[grpId] ?: faceAliasById[grpId]?.name

    @Synchronized
    override fun findGrpIdByNameAnyFace(name: String): Int? {
        if (name in cardIndexByName) return findGrpIdByName(name)
        val alias = faceAliases[name]?.singleOrNull() ?: return findGrpIdByName(name)
        findGrpIdByName(alias.parentName) ?: return null
        return catalogIdentityIds[alias.identityKey]
    }

    @Synchronized
    override fun findTokenGrpIdByName(name: String): Int? = (tokens[name] ?: tokens[name.removeSuffix(" Token")])?.singleOrNull()

    @Synchronized
    override fun findTokenGrpIdByScript(script: String): Int? {
        val id = catalogIdentityIds["token-script:$script"] ?: return null
        rows.findByGrpId(id)?.let { return id }
        val token = StaticData.instance().allTokens.getToken(script) ?: return null
        registerFace(token.rules.mainPart, id, emptyList(), 0, "token-script:$script")
        claim(id, "token-script:$script")
        val name = token.rules.mainPart.name
        tokens.getOrPut(name) { mutableSetOf() }.add(id)
        tokens.getOrPut(name.removeSuffix(" Token")) { mutableSetOf() }.add(id)
        return id
    }

    override fun findAllGrpIds(): List<Int> = primaryIds.toList()

    private fun lookupKey(name: String): String = name.lowercase()

    @Synchronized
    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? = rows.lookupModalOptions(cardGrpId)

    @Synchronized
    override fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? = rows.findAbilityInfo(abilityGrpId)

    @Synchronized
    override fun findAbilityLocalization(abilityGrpId: Int): AbilityLocalization? = rows.findAbilityLocalization(abilityGrpId)
}

private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

private fun parseParams(raw: String): Map<String, String> =
    raw
        .split('|')
        .mapNotNull {
            val parts = it.split('$', limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()
