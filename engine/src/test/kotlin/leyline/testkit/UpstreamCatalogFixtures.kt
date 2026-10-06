package leyline.testkit

import leyline.game.data.ForgeCardRepository

/**
 * Opt-in identities for upstream regression cards that have no retained native fixture.
 * Existing tests stay pinned to their native identities; only these new names use the
 * current Forge catalog. Rules execution still uses the same pinned Forge runtime.
 */
internal fun registerUpstreamCatalogCards(vararg names: String) {
    val catalog = ForgeCardRepository.open()
    val repo = TestCardRegistry.repo
    for (name in names) {
        val grpId = checkNotNull(catalog.findGrpIdByName(name)) { "Missing catalog card: $name" }
        val data = checkNotNull(catalog.findByGrpId(grpId))
        repo.registerData(data, name)
        for ((abilityId, _) in data.abilityIds + data.hiddenAbilityIds) {
            catalog.findAbilityInfo(abilityId)?.let { repo.registerAbilityInfo(abilityId, it) }
            catalog.findAbilityLocalization(abilityId)?.let { repo.registerAbilityLocalization(abilityId, it) }
        }
        catalog.lookupModalOptions(grpId)?.let { repo.registerModalOptions(grpId, it) }
    }
}
