package leyline.game.state

import forge.game.Game
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardTraitChanges
import forge.game.cost.Cost
import forge.game.keyword.Keyword
import forge.game.spellability.AbilityActivated
import forge.game.staticability.StaticAbilityMode
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.AbilityKeywordFamily
import leyline.bridge.types.ForgeCardId
import leyline.game.codes.SlotKind
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardData
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.testkit.BoardTest
import leyline.testkit.CardDataDeriver
import leyline.testkit.TestCardInjector

class AbilityRegistryTest :
    BoardTest({

        test("type-derived dual-land mana definitions retain their shared color identities") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Bayou", human, ZoneType.Battlefield)
                }
            val bayou =
                game.players[0]
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Bayou" }
            val cardData = checkNotNull(bridge.cardRepository.findByGrpId(checkNotNull(bridge.cardRepository.findGrpIdByName("Bayou"))))
            val registry = AbilityRegistry.build(bayou, cardData)
            val generated =
                bayou.manaAbilities
                    .filter { BasicLandAbilities.byTypeDerivedManaAbility(bayou, it) != null }
                    .associateBy { it.manaPart.origProduced }

            assertSoftly {
                generated.keys shouldBe setOf("B", "G")
                registry.forSpellAbility(generated.getValue("B").definitionId) shouldBe 1003
                registry.forSpellAbility(generated.getValue("G")) shouldBe 1005
            }
        }

        test("normalized frame events invalidate affected ability registries") {
            val (bridge, _, _) =
                startWithBoard { _, human, _ ->
                    addCard("Pacifism", human, ZoneType.Battlefield)
                }
            val card =
                bridge
                    .getPlayer(leyline.bridge.types.SeatId(1))!!
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val cardData = bridge.cardRepository.findByGrpId(bridge.cardRepository.findGrpIdByName(card.name)!!)
            val before = bridge.abilityRegistryFor(card, cardData)!!

            bridge.invalidateAbilityRegistries(
                listOf(GameEvent.ZoneChanged(ForgeCardId(card.id), Zone.Battlefield, Zone.Hand)),
            )

            val after = bridge.abilityRegistryFor(card, cardData)!!

            after.slotLayout shouldBe before.slotLayout
            after shouldNotBeSameInstanceAs before
        }

        test("Station activated ability maps to shared abilityGrpId 373") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, "Lumen-Class Frigate", ZoneType.Battlefield)
            val stationAbility =
                injected.card.spellAbilities.first { ability ->
                    ability.isActivatedAbility && ability.isIntrinsic && !ability.isManaAbility()
                }

            val registry = AbilityRegistry.build(injected.card, CardDataDeriver.fromForgeCard(injected.card, "Lumen-Class Frigate"))

            registry.forSpellAbility(stationAbility.id) shouldBe 373
        }

        test("single continuously granted activated ability maps to its hidden slot") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    human.setSpeed(4)
                    addCard("Gas Guzzler", human, ZoneType.Battlefield)
                    human.game.action.checkStaticAbilities(false)
                }
            val card =
                game.players[0]
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Gas Guzzler" }
            val granted =
                card.changedCardTraits
                    .cellSet()
                    .flatMap { (it.value as? CardTraitChanges)?.getAbilities().orEmpty() }
                    .filter { it.isActivatedAbility && !it.isManaAbility() }
            granted.shouldHaveSize(1)
            val ability = granted.single()
            val registry = AbilityRegistry.build(card, CardDataDeriver.fromForgeCard(card, card.name))

            assertSoftly {
                ability.grantorStatic.hostCard shouldBe card
                registry.grantedAbilityGrpId(ability) shouldBe 179264
                registry.grantedAbilityUniqueIndex(ability) shouldBe 0
            }
        }

        test("multiple continuously granted abilities retain their source slot") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    human.setSpeed(4)
                    addCard("Gas Guzzler", human, ZoneType.Battlefield)
                    human.game.action.checkStaticAbilities(false)
                }
            val card =
                game.players[0]
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Gas Guzzler" }
            val first =
                card.changedCardTraits
                    .cellSet()
                    .flatMap { (it.value as? CardTraitChanges)?.getAbilities().orEmpty() }
                    .single { it.isActivatedAbility && !it.isManaAbility() }
            val registry = AbilityRegistry.build(card, CardDataDeriver.fromForgeCard(card, card.name))

            registry.forSpellAbility(first) shouldBe 179264

            val second = first.copy(card, false).also { it.setGrantorStatic(first.grantorStatic) }
            card.addChangedCardTraits(
                listOf(second),
                emptyList(),
                emptyList(),
                emptyList(),
                { true },
                99L,
                99L,
                false,
            )

            assertSoftly {
                registry.forSpellAbility(first) shouldBe 179264
                registry.forSpellAbility(second) shouldBe 179264
            }
        }

        test("keyword-backed activated ability dispatches by activated index") {
            val cardName = "Ninja of the Deep Hours"
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Hand)
            val ninjutsuAbility =
                injected.card.spellAbilities.single { ability ->
                    ability.isActivatedAbility && ability.isIntrinsic && !ability.isManaAbility()
                }

            val registry = AbilityRegistry.build(injected.card, CardDataDeriver.fromForgeCard(injected.card, cardName))

            registry.forSpellAbility(ninjutsuAbility.id) shouldBe 5341
            registry.slotLayout.forgeIndexFor(5341) shouldBe 0
        }

        test("Reconfigure attach and unattach map to distinct activated slots") {
            val cardName = "Rabbit Battery"
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
            val activated =
                injected.card.spellAbilities
                    .filter { it.isActivatedAbility && it.isIntrinsic && !it.isManaAbility() }
            activated.shouldHaveSize(2)

            val attach = activated.single { it.api == ApiType.Attach }
            val unattach = activated.single { it.api == ApiType.Unattach }
            val registry = AbilityRegistry.build(injected.card, CardDataDeriver.fromForgeCard(injected.card, cardName))

            assertSoftly {
                registry.forSpellAbility(attach.id) shouldBe 147974
                registry.forSpellAbility(unattach.id) shouldBe 244
                registry.slotLayout.forgeIndexFor(147974) shouldBe 0
                registry.slotLayout.forgeIndexFor(244) shouldBe 1
            }
        }

        test("unclaimed intrinsic static maps to matching intrinsic ability slot") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Pacifism", human, ZoneType.Battlefield)
                }
            val pacifism =
                game.players[0]
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Pacifism" }
            val restriction =
                pacifism.staticAbilities.single {
                    it.checkMode(StaticAbilityMode.CantAttack) && it.checkMode(StaticAbilityMode.CantBlock)
                }

            val registry = AbilityRegistry.build(pacifism, CardDataDeriver.fromForgeCard(pacifism, "Pacifism"))

            registry.forStaticAbility(restriction.id) shouldBe 1083
        }

        test("unclaimed printed static maps with legacy slot data") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Pacifism", human, ZoneType.Battlefield)
                }
            val pacifism =
                game.players[0]
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Pacifism" }
            val restriction =
                pacifism.staticAbilities.single {
                    it.checkMode(StaticAbilityMode.CantAttack) && it.checkMode(StaticAbilityMode.CantBlock)
                }
            restriction.setIntrinsic(false)
            val cardData =
                CardDataDeriver.fromForgeCard(pacifism, "Pacifism").copy(
                    abilityIds = listOf(1027 to 605, 1083 to 62428),
                    abilityKinds = emptyList(),
                )

            val registry = AbilityRegistry.build(pacifism, cardData)

            registry.forStaticAbility(restriction.id) shouldBe 1083
        }

        test("planeswalker loyalty abilities map to distinct abilityGrpId slots") {
            val cardName = "Chandra, Torch of Defiance"
            val (b, game, _) = startWithBoard { _, _, _ -> }

            // Inject planeswalker onto battlefield
            val injected =
                TestCardInjector.inject(
                    b,
                    1,
                    cardName,
                    ZoneType.Battlefield,
                )
            val card = injected.card

            // Derive CardData from the live card (has game context → full abilities),
            // stamped with client identity from the fixture.
            val cardData = CardDataDeriver.fromForgeCard(card, cardName)

            // Chandra has 4 loyalty abilities (all activated, non-mana)
            val loyaltyAbilities =
                card.spellAbilities
                    .filter { it.isActivatedAbility && !it.isManaAbility() }
            loyaltyAbilities.shouldHaveSize(4)

            // Chandra's fixture has ability ids for all 4 loyalty abilities.
            cardData.abilityIds.shouldHaveSize(4)

            // Build registry
            val registry = AbilityRegistry.build(card, cardData)

            // Each loyalty ability should map to a distinct abilityGrpId
            val mappedGrpIds =
                loyaltyAbilities.map { sa ->
                    val mapped = registry.forSpellAbility(sa.id)
                    mapped.shouldNotBeNull()
                    mapped
                }
            mappedGrpIds.distinct().shouldHaveSize(4)

            // The mapped grpIds should match the slots from cardData.abilityIds
            val expectedSlots = cardData.abilityIds.map { it.first }
            mappedGrpIds shouldBe expectedSlots
        }

        // When CardData carries Arena-style
        // abilityKinds with a non-activated slot (trigger/static) interleaved
        // before the activated abilities, the registry must skip those slots
        // when assigning Forge activated SAs to abilityGrpIds.
        test("trigger slot interleaved before activated abilities does not shift mapping") {
            val cardName = "Kaito, Cunning Infiltrator"
            val (b, _, _) = startWithBoard { _, _, _ -> }

            val injected =
                TestCardInjector.inject(
                    b,
                    1,
                    cardName,
                    ZoneType.Battlefield,
                )
            val card = injected.card

            val activated =
                card.spellAbilities
                    .filter { it.isActivatedAbility && !it.isManaAbility() && it.isIntrinsic }
            activated.shouldHaveSize(3) // [+1], [-2], [-9]

            // Simulate Arena's actual slot layout: trigger first, then activated.
            // The grpIds match Arena DB for Kaito (grpId 93757):
            //   slot 0 = 175794 (trigger)
            //   slot 1 = 175795 ([+1])
            //   slot 2 = 175796 ([-2])
            //   slot 3 = 175798 ([-9])
            val arenaShapedCardData =
                CardData(
                    grpId = 93757,
                    titleId = 0,
                    power = "",
                    toughness = "3",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    abilityIds =
                        listOf(
                            175794 to 0,
                            175795 to 0,
                            175796 to 0,
                            175798 to 0,
                        ),
                    abilityKinds =
                        listOf(
                            SlotKind.Intrinsic,
                            SlotKind.Activated,
                            SlotKind.Activated,
                            SlotKind.Activated,
                        ),
                    manaCost = emptyList(),
                )

            val registry = AbilityRegistry.build(card, arenaShapedCardData)

            // Forward direction (forSpellAbility): each Forge activated SA
            // should map to an Arena Activated slot, skipping the trigger at
            // slot 0. Order in card.spellAbilities is [+1], [-2], [-9] per
            // the Forge card definition.
            val mapped =
                activated.map { sa ->
                    val grp = registry.forSpellAbility(sa.id)
                    grp.shouldNotBeNull()
                    grp
                }
            mapped shouldBe listOf(175795, 175796, 175798)

            // Reverse direction (slotLayout.forgeIndexFor): the dispatch
            // path used by ActionPerformer.resolveAbilityIndex. Indices are
            // into the Forge-order non-mana activated list (which excludes
            // triggers/statics), so the trigger at slot 0 must return null
            // and each Activated slot must produce its position among the
            // Activated slots — not its raw slot index.
            assertSoftly(registry.slotLayout) {
                forgeIndexFor(175794).shouldBeNull() // trigger — not activatable
                forgeIndexFor(175795) shouldBe 0 // [+1]
                forgeIndexFor(175796) shouldBe 1 // [-2]
                forgeIndexFor(175798) shouldBe 2 // [-9]
            }
        }

        test("raw intrinsic categories separate mixed static and trigger slots") {
            val cardName = "Heliod, Sun-Crowned"
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val card = TestCardInjector.inject(bridge, 1, cardName, ZoneType.Battlefield).card
            val cardData = CardDataDeriver.fromForgeCard(card, cardName)
            val devotionStatic = card.staticAbilities.single { it.getParam("CheckSVar") == "X" }
            val lifeGainTrigger = card.triggers.single { it.mode == forge.game.trigger.TriggerType.LifeGained }

            val registry = AbilityRegistry.build(card, cardData)

            assertSoftly {
                cardData.abilityCategories shouldBe listOf(3, 3, 2, 1)
                registry.forStaticAbility(devotionStatic.id) shouldBe 100654
                registry.forTrigger(lifeGainTrigger.id) shouldBe 136666
            }
        }

        test("mana slot interleaved before activated land ability does not shift mapping") {
            val cardName = "Racers' Ring"
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
            val card = injected.card
            val manaAbility = card.spellAbilities.single { it.isManaAbility() && it.isIntrinsic }
            val drawAbility =
                card.spellAbilities.single {
                    it.isActivatedAbility && !it.isManaAbility() && it.isIntrinsic
                }
            val cardData = CardDataDeriver.fromForgeCard(card, cardName)

            val registry = AbilityRegistry.build(card, cardData)

            assertSoftly {
                cardData.abilityKinds shouldBe listOf(SlotKind.Intrinsic, SlotKind.Mana, SlotKind.Activated)
                registry.forSpellAbility(manaAbility.id) shouldBe 1131
                registry.forSpellAbility(drawAbility.id) shouldBe 149629
                registry.slotLayout.forgeIndexFor(149629) shouldBe 0
            }
        }

        test("Evolved Sleeper activated abilities map to per-ability rows") {
            val cardName = "Evolved Sleeper"
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
            val activatedAbilities =
                injected.card.spellAbilities
                    .filter { it.isActivatedAbility && it.isIntrinsic && !it.isManaAbility() }
            val cardData = CardDataDeriver.fromForgeCard(injected.card, cardName)

            val registry = AbilityRegistry.build(injected.card, cardData)

            assertSoftly {
                activatedAbilities shouldHaveSize 3
                registry.forSpellAbility(activatedAbilities[0].id) shouldBe 152784
                registry.forSpellAbility(activatedAbilities[1].id) shouldBe 152785
                registry.forSpellAbility(activatedAbilities[2].id) shouldBe 152786
            }
        }

        test("typed definition references resolve spell trigger and static namespaces") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val bondwarden = TestCardInjector.inject(b, 1, "Enduring Bondwarden", ZoneType.Battlefield).card
            val ninja = TestCardInjector.inject(b, 1, "Ninja of the Deep Hours", ZoneType.Hand).card
            val pacifism = TestCardInjector.inject(b, 1, "Pacifism", ZoneType.Battlefield).card
            val activated = ninja.spellAbilities.single { it.isActivatedAbility && !it.isManaAbility() }
            val backupTrigger =
                bondwarden
                    .getKeywords()
                    .first { it.keyword == Keyword.BACKUP }
                    .triggers
                    .single()
            val restriction =
                pacifism.staticAbilities.single {
                    it.checkMode(StaticAbilityMode.CantAttack) && it.checkMode(StaticAbilityMode.CantBlock)
                }
            val bondwardenRegistry = AbilityRegistry.build(bondwarden, CardDataDeriver.fromForgeCard(bondwarden, bondwarden.name))
            val ninjaRegistry = AbilityRegistry.build(ninja, CardDataDeriver.fromForgeCard(ninja, ninja.name))
            val pacifismRegistry = AbilityRegistry.build(pacifism, CardDataDeriver.fromForgeCard(pacifism, pacifism.name))

            assertSoftly {
                ninjaRegistry.resolve(AbilityDefinitionRef.SpellAbility(activated.definitionId))?.definition shouldBe
                    AbilityDefinitionRef.SpellAbility(activated.definitionId)
                bondwardenRegistry.resolve(AbilityDefinitionRef.Trigger(backupTrigger.definitionId))?.keywordFamily shouldBe
                    AbilityKeywordFamily.Backup
                pacifismRegistry.resolve(AbilityDefinitionRef.StaticAbility(restriction.definitionId))?.abilityGrpId shouldBe 1083
                bondwardenRegistry.resolve(AbilityDefinitionRef.StaticAbility(backupTrigger.definitionId)) shouldBe null
            }
        }

        test("copied same-shape activated abilities resolve by definition") {
            val card = Card(999, null as Game?)
            card.name = "Same Shape"

            fun ability(): AbilityActivated =
                object : AbilityActivated(card, Cost("1", true), null) {
                    override fun resolve() = Unit
                }.also {
                    it.api = ApiType.Draw
                    card.addSpellAbility(it)
                }
            val first = ability()
            val second = ability()
            val firstCopy = first.copy()
            val secondCopy = second.copy()
            val cardData =
                CardData(
                    grpId = 9000,
                    titleId = 0,
                    power = "",
                    toughness = "",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    abilityIds = listOf(9001 to 0, 9002 to 0),
                    abilityKinds = listOf(SlotKind.Activated, SlotKind.Activated),
                    manaCost = emptyList(),
                )
            val registry = AbilityRegistry.build(card, cardData)

            assertSoftly {
                first.api shouldBe second.api
                first.payCosts.toSimpleString() shouldBe second.payCosts.toSimpleString()
                firstCopy.id shouldNotBe first.id
                secondCopy.id shouldNotBe second.id
                firstCopy.definitionId shouldBe first.definitionId
                secondCopy.definitionId shouldBe second.definitionId
                registry.forSpellAbility(firstCopy) shouldBe 9001
                registry.forSpellAbility(secondCopy) shouldBe 9002
            }
        }
    })
