package leyline.game.data

import com.google.protobuf.util.JsonFormat
import forge.ImageKeys
import forge.StaticData
import forge.card.GamePieceType
import forge.game.card.Card
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.*
import leyline.ForgeCatalogTag
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.StaticChoiceIds
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GrpIdResolver
import leyline.testkit.annotationsOfType
import leyline.testkit.battlefield
import leyline.testkit.detailInt
import leyline.testkit.exile
import leyline.testkit.graveyard
import leyline.testkit.hand
import leyline.tooling.headless.MatchFlowHarness
import leyline.tooling.headless.TestCardRegistry
import leyline.tooling.headless.dumpDiagnostics
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File

class ForgeCatalogProbeTest :
    FunSpec({
        tags(IntegrationTag, ForgeCatalogTag)
        timeout = 600_000L
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            check(
                java.nio.file.Files
                    .isDirectory(
                        java.nio.file.Paths
                            .get(System.getenv("LEYLINE_CARD_DB")),
                    ),
            )
            check(runCatching { Class.forName("org.sqlite.JDBC") }.isFailure) { "SQLite driver must be absent" }
            check(javaClass.classLoader.getResource("test-cards/forest.yaml") == null) { "Fixture resources must be absent" }
        }
        afterEach { TestCardRegistry.repo.registeredCount shouldBe 0 }

        test("targeted activated ability resolves with a generated ability identity") {
            probe("activated", "humanbattlefield=Goblin Fireslinger\naibattlefield=Centaur Courser") { repo ->
                activateAbility("Goblin Fireslinger").shouldBeTrue()
                passUntil(5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
                selectTargets(listOf(2))
                passUntil(10) { ai.life == 19 }.shouldBeTrue()
                ai.life shouldBe 19
                val abilities =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.type == GameObjectType.Ability }
                check(abilities.isNotEmpty())
                check(abilities.any { repo.findAbilityInfo(it.grpId) != null })
            }
        }
        test("modal spell accepts generated option IDs and pays a selected extra cost") {
            probe(
                "modal",
                "humanhand=Thunder Magic\nhumanbattlefield=Mountain;Mountain;Mountain;Mountain\naibattlefield=Grizzly Bears",
            ) { repo ->
                val prompt = castSpellUntilCastingTimeOptionsReq("Thunder Magic")
                val modal = prompt.getCastingTimeOptionReq(0).modalReq
                modal.modalOptionsCount shouldBe 3
                check(modal.modalOptionsList.all { !repo.findAbilityLocalization(it.grpId)?.text.isNullOrBlank() })
                modal
                    .getModalOptions(1)
                    .getModeCost(0)
                    .manaCost.count shouldBe 3
                val paymentStart = messageSnapshot()
                respondModalChoice(listOf(modal.getModalOptions(1).grpId))
                selectTargets(listOf(ai.battlefield.iid("Grizzly Bears")))
                passUntilResolved()
                val tappedLandsDuringPayment =
                    messagesSince(paymentStart)
                        .annotationsOfType(AnnotationType.TappedUntappedPermanent)
                        .filter { it.detailInt("tapped") == 1 }
                        .flatMap { it.affectedIdsList }
                        .distinct()
                        .size
                assertSoftly {
                    ai.graveyard.cards.count { it.name == "Grizzly Bears" } shouldBe 1
                    human.graveyard.cards.count { it.name == "Thunder Magic" } shouldBe 1
                    tappedLandsDuringPayment shouldBe 4
                }
            }
        }
        test("token spell produces distinct resolvable token metadata in GRE") {
            probe("tokens", "humanhand=Raise the Alarm\nhumanbattlefield=Plains;Plains") { repo ->
                assertSoftly {
                    castSpellByName("Raise the Alarm").shouldBeTrue()
                    passUntil(10) { human.battlefield.cards.count { it.isToken } == 2 }.shouldBeTrue()
                    human.battlefield.cards.count { it.isToken } shouldBe 2
                }
                val tokenObjects =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.type == GameObjectType.Token }
                check(tokenObjects.map { it.instanceId }.distinct().size == 2)
                check(tokenObjects.all { repo.findByGrpId(it.grpId)?.types?.contains(CardType.Creature.number) == true })
            }
        }
        test("generated tokens resolve from their Forge token script identity") {
            val repo = ForgeCardRepository.open()
            val scripts =
                mapOf(
                    "Human Soldier Token" to "w_1_1_human_soldier",
                    "Servo Token" to "c_1_1_a_servo",
                    "Clue Token" to "c_a_clue_draw",
                    "Treasure Token" to "c_a_treasure_sac",
                )

            scripts.forEach { (name, script) ->
                val token = Card(1, null, null)
                token.name = name
                token.setGamePieceType(GamePieceType.TOKEN)
                token.imageKey = ImageKeys.getTokenKey("$script|TST")

                val grpId = GrpIdResolver.resolve(token, repo)
                grpId shouldNotBe 0
                repo.findNameByGrpId(grpId) shouldBe name
            }
        }
        test("keyword-generated token publishes resolvable metadata") {
            probe("keyword-token", puzzleFile = "data/puzzles/forge-catalog-clue-token.pzl") { repo ->
                castSpellByName("Thraben Inspector").shouldBeTrue()
                passUntil(10) { human.battlefield.cards.any { it.name == "Clue Token" } }.shouldBeTrue()

                val token =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .last { it.type == GameObjectType.Token }
                token.grpId shouldNotBe 0
                repo.findNameByGrpId(token.grpId) shouldBe "Clue Token"
            }
        }
        test("activated transform publishes the second face identity") {
            probe("transform", "humanbattlefield=Concealing Curtains;Swamp;Swamp;Swamp") { repo ->
                val card = human.battlefield.card("Concealing Curtains")
                assertSoftly {
                    activateAbility("Concealing Curtains").shouldBeTrue()
                    passUntil(10) { card.isBackSide }.shouldBeTrue()
                    card.name shouldBe "Revealing Eye"
                }
                val backId = requireNotNull(repo.findGrpIdByName("Revealing Eye"))
                check(
                    allMessages
                        .filter {
                            it.hasGameStateMessage()
                        }.flatMap { it.gameStateMessage.gameObjectsList }
                        .any { it.grpId == backId },
                )
                check(repo.findLinkedFaces(requireNotNull(repo.findGrpIdByName("Concealing Curtains"))).contains(backId))
                val back = requireNotNull(repo.findByGrpId(backId))
                val triggerIds =
                    back.abilityIds
                        .zip(back.abilityCategories)
                        .filter { it.second == 2 }
                        .map { it.first.first }
                        .toSet()
                check(
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .any { it.type == GameObjectType.Ability && it.grpId in triggerIds },
                )
            }
        }
        test("flashback offer and lifecycle use Forge-derived keyword cost metadata") {
            probe("flashback", "humanhand=Think Twice\nhumanbattlefield=Island;Island;Island;Island;Island;Island") { repo ->
                val cardId = requireNotNull(repo.findGrpIdByName("Think Twice"))
                val keyword = requireNotNull(repo.findKeywordAbilityGrpId(cardId, KeywordAbilityIds.FLASHBACK))
                check(repo.findAbilityInfo(keyword)?.manaCost?.isNotEmpty() == true)
                assertSoftly {
                    castSpellByName("Think Twice").shouldBeTrue()
                    passUntil(10) { human.graveyard.cards.any { it.name == "Think Twice" } }.shouldBeTrue()
                    human.graveyard.cards.count { it.name == "Think Twice" } shouldBe 1
                }
                val before = human.hand.cards.size
                assertSoftly {
                    castFromGraveyard("Think Twice").shouldBeTrue()
                    passUntil(10) { human.exile.cards.any { it.name == "Think Twice" } }.shouldBeTrue()
                    human.exile.cards.count { it.name == "Think Twice" } shouldBe 1
                    human.hand.cards.size shouldBe before + 1
                }
            }
        }
        test("adventure resolves into exile and the creature is then cast from exile") {
            probe(
                "adventure",
                "humanhand=Beanstalk Giant\nhumanbattlefield=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest",
            ) { repo ->
                val parent = requireNotNull(repo.findGrpIdByName("Beanstalk Giant"))
                val other = requireNotNull(repo.findGrpIdByName("Fertile Footsteps"))
                check(repo.findLinkedFaces(parent).contains(other))
                val action =
                    allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                        it.actionType ==
                            ActionType.CastAdventure
                    }
                submitAction(action)
                passUntil(10) { allMessages.any { it.hasSearchReq() || it.hasSearchFromGroupsReq() } }.shouldBeTrue()
                // An unsuccessful search is legal and avoids a library-order dependency.
                val grouped = allMessages.lastOrNull { it.hasSearchFromGroupsReq() }
                if (grouped != null) respondToGroupedSearchFail() else respondToSearch(emptyList())
                assertSoftly {
                    passUntil(10) { human.exile.cards.any { it.name == "Beanstalk Giant" } }.shouldBeTrue()
                    human.exile.cards.count { it.name == "Beanstalk Giant" } shouldBe 1
                    castFromExile("Beanstalk Giant").shouldBeTrue()
                    passUntil(10) { human.battlefield.cards.any { it.name == "Beanstalk Giant" } }.shouldBeTrue()
                    human.battlefield.cards.count { it.name == "Beanstalk Giant" } shouldBe 1
                }
            }
        }
        test("split halves keep distinct cast identities and resolve their own effects") {
            probe(
                "split",
                puzzleFile = "data/puzzles/split-dead-gone.pzl",
            ) { repo ->
                val parent = requireNotNull(repo.findGrpIdByName("Dead // Gone"))
                val dead = requireNotNull(repo.findGrpIdByNameAnyFace("Dead"))
                val gone = requireNotNull(repo.findGrpIdByNameAnyFace("Gone"))
                val firstOffers =
                    allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.filter {
                        it.actionType == ActionType.Cast && it.grpId in setOf(dead, gone)
                    }
                firstOffers.map { it.grpId }.toSet() shouldBe setOf(dead, gone)

                submitAction(firstOffers.first { it.grpId == gone })
                selectTargets(listOf(ai.battlefield.iid("Grizzly Bears")))
                passUntilResolved()
                ai.hand.cards.count { it.name == "Grizzly Bears" } shouldBe 1
                passUntil(5) {
                    allMessages.lastOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq?.actionsList?.any {
                        it.actionType == ActionType.Cast && it.grpId == dead
                    } == true
                }.shouldBeTrue()

                val deadOffer =
                    allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                        it.actionType == ActionType.Cast && it.grpId == dead
                    }
                submitAction(deadOffer)
                selectTargets(listOf(ai.battlefield.iid("Walking Corpse")))
                passUntilResolved()
                assertSoftly {
                    ai.graveyard.cards.count { it.name == "Walking Corpse" } shouldBe 1
                    human.graveyard.cards.count { it.name == "Dead // Gone" } shouldBe 2
                    repo.findGrpIdByName("Gone") shouldBe parent
                }
            }
        }
        test("Room doors use combined metadata and preserve both unlocked designations") {
            probe(
                "room",
                puzzleFile = "data/puzzles/room-surgical-suite.pzl",
            ) { repo ->
                val parent = requireNotNull(repo.findGrpIdByName("Surgical Suite // Hospital Room"))
                val suite = requireNotNull(repo.findGrpIdByNameAnyFace("Surgical Suite"))
                val hospital = requireNotNull(repo.findGrpIdByNameAnyFace("Hospital Room"))
                requireNotNull(repo.findByGrpId(parent)).linkedFaceGrpIds shouldBe listOf(suite, hospital)
                val corpse = human.graveyard.iid("Walking Corpse")
                val left =
                    allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                        it.actionType == ActionType.CastLeftRoom
                    }
                submitAction(left)
                selectTargets(listOf(corpse))
                assertSoftly {
                    passUntil(10) { human.battlefield.cards.any { it.name == "Walking Corpse" } }.shouldBeTrue()
                    human.battlefield.cards.count { it.name == "Walking Corpse" } shouldBe 1
                    passUntil(5) {
                        allMessages.lastOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq?.actionsList?.any {
                            it.actionType == ActionType.CastRightRoom
                        } == true
                    }.shouldBeTrue()
                }
                val right =
                    allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                        it.actionType == ActionType.CastRightRoom
                    }
                submitAction(right)
                passUntilResolved()

                val room = human.battlefield.cards.single { it.isRoom }
                val roomIid = human.battlefield.iid(room)
                val designations =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.persistentAnnotationsList }
                        .filter { AnnotationType.Designation in it.typeList && roomIid in it.affectedIdsList }
                        .flatMap { annotation ->
                            annotation.detailsList
                                .filter { it.key == "DesignationType" }
                                .flatMap { it.valueInt32List }
                        }.toSet()
                assertSoftly {
                    room.unlockedRooms shouldBe setOf(forge.card.CardStateName.LeftSplit, forge.card.CardStateName.RightSplit)
                    designations.containsAll(setOf(19, 20)).shouldBeTrue()
                }
            }
        }
        test("Specialize selects a color and publishes the resulting form identity") {
            probe(
                "specialize",
                puzzleFile = "data/puzzles/specialize-ambergris.pzl",
            ) { repo ->
                val base = requireNotNull(repo.findGrpIdByName("Ambergris, Citadel Agent"))
                val tyranny = requireNotNull(repo.findGrpIdByNameAnyFace("Ambergris, Agent of Tyranny"))
                val ambergris = human.battlefield.card("Ambergris, Citadel Agent")
                val ambergrisIid = human.battlefield.iid(ambergris)
                activateAbility("Ambergris, Citadel Agent").shouldBeTrue()
                val colorReq = lastSelectNReq()
                colorReq.staticList shouldBe StaticList.Colors
                respondToSelectN(listOf(requireNotNull(StaticChoiceIds.colorIdForName("Black"))))
                val discardReq = lastSelectNReq()
                respondToSelectN(listOf(findInstanceId(discardReq.idsList, "Swamp")))
                passUntil(10) { ambergris.name == "Ambergris, Agent of Tyranny" }.shouldBeTrue()
                val formObjects =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.instanceId == ambergrisIid }
                assertSoftly {
                    ambergris.netPower shouldBe 4
                    ambergris.netToughness shouldBe 3
                    formObjects.last().grpId shouldBe tyranny
                    repo.findGrpIdByName("Ambergris, Agent of Tyranny") shouldBe base
                    human.graveyard.cards.count { it.name == "Swamp" } shouldBe 1
                }

                holdNextOptionalAction()
                passUntil(10) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
                declareAttackers(listOf(ambergrisIid))
                passUntil(5) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
                respondToOptionalAction(accept = true)
                passUntil(5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
                selectTargets(listOf(ai.battlefield.iid("Grizzly Bears")))
                passUntil(10) { ai.graveyard.cards.any { it.name == "Grizzly Bears" } }.shouldBeTrue()
                assertSoftly {
                    ai.graveyard.cards.count { it.name == "Grizzly Bears" } shouldBe 1
                    human.graveyard.cards.count { it.name == "Walking Corpse" } shouldBe 1
                    human.hand.cards.count { it.name == "Unsummon" } shouldBe 2
                }

                val unsummon = requireNotNull(repo.findGrpIdByName("Unsummon"))
                passUntil(10) {
                    allMessages.lastOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq?.actionsList?.any {
                        it.actionType == ActionType.Cast && it.grpId == unsummon
                    } == true
                }.shouldBeTrue()
                castSpellByName("Unsummon").shouldBeTrue()
                selectTargets(listOf(ambergrisIid))
                passUntil(10) { human.hand.cards.any { it.name == "Ambergris, Agent of Tyranny" } }.shouldBeTrue()
                val movedAmbergris = human.hand.card("Ambergris, Agent of Tyranny")
                val movedAmbergrisIid = human.hand.iid(movedAmbergris)
                val movedObject =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .last { it.instanceId == movedAmbergrisIid && it.zoneId == ZoneIds.P1_HAND }
                assertSoftly {
                    movedObject.grpId shouldBe tyranny
                    movedAmbergrisIid shouldNotBe ambergrisIid
                }
            }
        }
        test("triggered removal resolves using a derived trigger slot") {
            probe(
                "trigger",
                "humanhand=Ravenous Chupacabra\nhumanbattlefield=Swamp;Swamp;Swamp;Swamp\naibattlefield=Centaur Courser",
            ) { repo ->
                castSpellByName("Ravenous Chupacabra").shouldBeTrue()
                passUntil(5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
                selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
                passUntil(10) { ai.graveyard.cards.any { it.name == "Centaur Courser" } }.shouldBeTrue()
                ai.graveyard.cards.count { it.name == "Centaur Courser" } shouldBe 1
                check(
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .any { it.type == GameObjectType.Ability && repo.findAbilityInfo(it.grpId)?.category == 2 },
                )
            }
        }
        test("planeswalker activation retains distinct slots and changes loyalty") {
            probe("planeswalker", "humanbattlefield=Jace Beleren|Counters:LOYALTY=3") { repo ->
                val card = human.battlefield.card("Jace Beleren")
                val before = human.hand.cards.size
                val aiBefore = ai.hand.cards.size
                val data = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName("Jace Beleren"))))
                assertSoftly {
                    data.abilityKinds.count { it == leyline.game.codes.SlotKind.Activated } shouldBe 3
                    activateAbility("Jace Beleren", 0).shouldBeTrue()
                    passUntil(10) { human.hand.cards.size == before + 1 && ai.hand.cards.size == aiBefore + 1 }.shouldBeTrue()
                    human.hand.cards.size shouldBe before + 1
                    ai.hand.cards.size shouldBe aiBefore + 1
                    card.getCounters(forge.game.card.CounterEnumType.LOYALTY) shouldBe 5
                }
            }
        }
        test("every catalog definition resolves or reports an explicit unsupported card shape") {
            val names =
                StaticData
                    .instance()
                    .commonCards.uniqueCards
                    .map { it.name }
                    .distinct()
                    .sorted()
            val repo = ForgeCardRepository.open()
            val failures = mutableListOf<String>()
            for (name in names) {
                runCatching { requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName(name)))) }
                    .onFailure { failures += "$name: ${it.message}" }
            }
            val result =
                listOf(
                    "total=${names.size}",
                    "cards=${repo.findAllGrpIds().size}",
                    "identities=${repo.identityKeys.size}",
                    "failures=${failures.size}\n",
                ).joinToString(" ") +
                    failures.joinToString("\n")
            File("build/forge-catalog-probe/catalog.txt").apply {
                parentFile.mkdirs()
                writeText(result)
            }
            println("FORGE_CATALOG_AUDIT $result")
            check(failures.isEmpty()) { result }
            repo.catalogIdentityIds.values
                .toSet()
                .size shouldBe repo.catalogIdentityIds.size
            repo.identityKeys.filterKeys { it >= 300_000_000 }.forEach { (id, key) -> repo.catalogIdentityIds[key] shouldBe id }
        }
        test("identities and metadata are independent of registration order") {
            val names =
                listOf("Goblin Fireslinger", "Thunder Magic", "Raise the Alarm", "Concealing Curtains", "Think Twice", "Beanstalk Giant")
            val a = ForgeCardRepository.open()
            val b = ForgeCardRepository.open()
            names.forEach { requireNotNull(a.findGrpIdByName(it)) }
            names.reversed().forEach { requireNotNull(b.findGrpIdByName(it)) }
            a.catalogVersion shouldBe b.catalogVersion
            names.forEach { a.findGrpIdByName(it) shouldBe b.findGrpIdByName(it) }
            names.mapNotNull(a::findGrpIdByName).forEach { a.findByGrpId(it) shouldBe b.findByGrpId(it) }
        }
        test("display metadata preserves Forge-only subtypes and keywords") {
            val repo = ForgeCardRepository.open()
            val chaplain = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName("Primaris Chaplain"))))
            val angel = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName("Serra Angel"))))

            check("Astartes" in chaplain.subtypeNames)
            check(angel.keywordNames.any { it.equals("Flying", ignoreCase = true) })
            check(angel.keywordNames.any { it.equals("Vigilance", ignoreCase = true) })
        }
        test("same-named token definitions keep source-specific identities") {
            val repo = ForgeCardRepository.open()
            val jadar = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName("Jadar, Ghoulcaller of Nephalia"))))
            val moan = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName("Moan of the Unhallowed"))))
            val decayedToken = jadar.tokenGrpIds.values.single()
            val ordinaryToken = moan.tokenGrpIds.values.single()

            check(decayedToken != ordinaryToken)
            repo.findNameByGrpId(decayedToken) shouldBe repo.findNameByGrpId(ordinaryToken)
            check(requireNotNull(repo.findByGrpId(decayedToken)).keywordNames.any { it.equals("Decayed", ignoreCase = true) })
            check(requireNotNull(repo.findByGrpId(ordinaryToken)).keywordNames.none { it.equals("Decayed", ignoreCase = true) })
            repo.findTokenGrpIdByName("Zombie") shouldBe null
            repo.findGrpIdByName("Zombie") shouldBe null
        }
        test("secondary faces resolve from a cold repository without admitting token names") {
            val first = ForgeCardRepository.open()
            val revealingEye = requireNotNull(first.findGrpIdByNameAnyFace("Revealing Eye"))
            val fertileFootsteps = requireNotNull(first.findGrpIdByNameAnyFace("Fertile Footsteps"))
            val restarted = ForgeCardRepository.open()

            assertSoftly {
                restarted.findNameByGrpId(revealingEye) shouldBe "Revealing Eye"
                restarted.findNameByGrpId(fertileFootsteps) shouldBe "Fertile Footsteps"
                requireNotNull(restarted.findByGrpId(revealingEye)).grpId shouldBe revealingEye
                requireNotNull(restarted.findByGrpId(fertileFootsteps)).grpId shouldBe fertileFootsteps
                restarted.findGrpIdByName("Zombie") shouldBe null
            }
        }
        test("deck entry resolves case and linked-face names to the primary identity") {
            val repo = ForgeCardRepository.open()
            val delver = requireNotNull(repo.findGrpIdByName("Delver of Secrets"))
            val backFace = requireNotNull(repo.findGrpIdByNameAnyFace("Insectile Aberration"))
            val kenrith = requireNotNull(repo.findGrpIdByName("Kenrith, the Returned King"))

            assertSoftly {
                repo.findDeckGrpIdByName("delver of secrets") shouldBe delver
                repo.findDeckGrpIdByName("Insectile Aberration") shouldBe delver
                repo.findDeckGrpIdByName("Delver of Secrets // Insectile Aberration") shouldBe delver
                repo.findDeckGrpIdByNameAndSet("Insectile Aberration", "ISD") shouldBe delver
                repo.findGrpIdByNameAnyFace("Insectile Aberration") shouldBe backFace
                repo.findDeckGrpIdByName("Delver of Secrets // Revealing Eye") shouldBe null
                repo.findDeckGrpIdByName("kenrith, the returned king") shouldBe kenrith
                repo.findDeckGrpIdByName("Kenrith, the Returned King (leader)") shouldBe null
            }
        }
        test("catalog names include linked faces and their combined name") {
            val repo = ForgeCardRepository.open()
            val delver = requireNotNull(repo.findGrpIdByName("Delver of Secrets"))

            repo.findNamesByGrpId(delver) shouldBe
                listOf(
                    "Delver of Secrets",
                    "Insectile Aberration",
                    "Delver of Secrets // Insectile Aberration",
                )
        }
        test("combined and specialize faces keep cold catalog identities") {
            val first = ForgeCardRepository.open()
            val splitParent = requireNotNull(first.findGrpIdByName("Dead // Gone"))
            val dead = requireNotNull(first.findGrpIdByNameAnyFace("Dead"))
            val gone = requireNotNull(first.findGrpIdByNameAnyFace("Gone"))
            val roomParent = requireNotNull(first.findGrpIdByName("Surgical Suite // Hospital Room"))
            val suite = requireNotNull(first.findGrpIdByNameAnyFace("Surgical Suite"))
            val hospital = requireNotNull(first.findGrpIdByNameAnyFace("Hospital Room"))
            val specializeParent = requireNotNull(first.findGrpIdByName("Ambergris, Citadel Agent"))
            val tyranny = requireNotNull(first.findGrpIdByNameAnyFace("Ambergris, Agent of Tyranny"))
            val restarted = ForgeCardRepository.open()

            assertSoftly {
                listOf(splitParent, dead, gone).distinct().size shouldBe 3
                first.findGrpIdByName("Gone") shouldBe splitParent
                requireNotNull(first.findByGrpId(splitParent)).linkedFaceGrpIds shouldBe listOf(dead, gone)
                listOf(roomParent, suite, hospital).distinct().size shouldBe 3
                first.findGrpIdByName("Hospital Room") shouldBe roomParent
                requireNotNull(first.findByGrpId(roomParent)).linkedFaceGrpIds shouldBe listOf(suite, hospital)
                first.findGrpIdByName("Ambergris, Agent of Tyranny") shouldBe specializeParent
                requireNotNull(first.findByGrpId(specializeParent)).linkedFaceGrpIds.size shouldBe 5
                restarted.findNameByGrpId(gone) shouldBe "Gone"
                restarted.findNameByGrpId(hospital) shouldBe "Hospital Room"
                restarted.findNameByGrpId(tyranny) shouldBe "Ambergris, Agent of Tyranny"
                requireNotNull(restarted.findByGrpId(gone)).grpId shouldBe gone
                requireNotNull(restarted.findByGrpId(hospital)).grpId shouldBe hospital
                requireNotNull(restarted.findByGrpId(tyranny)).grpId shouldBe tyranny
            }
        }
        test("standalone primary names win over colliding face aliases") {
            val repo = ForgeCardRepository.open()
            val primaryNames =
                StaticData
                    .instance()
                    .commonCards.uniqueCards
                    .mapTo(mutableSetOf()) { it.name }
            val collision =
                StaticData
                    .instance()
                    .commonCards.uniqueCards
                    .asSequence()
                    .map { it.rules }
                    .flatMap { rules -> rules.allFaces.asSequence().map { rules.name to it.name } }
                    .first { (parent, face) -> face != parent && face in primaryNames }
            val parent = requireNotNull(repo.findGrpIdByName(collision.first))
            val primary = requireNotNull(repo.findGrpIdByName(collision.second))
            val linkedFace = repo.findLinkedFaces(parent).single { repo.findNameByGrpId(it) == collision.second }

            assertSoftly {
                repo.findGrpIdByNameAnyFace(collision.second) shouldBe primary
                repo.findNameByGrpId(primary) shouldBe collision.second
                (linkedFace in repo.catalogIdentityIds.values).shouldBeTrue()
                linkedFace shouldNotBe primary
            }
        }
    })

private fun probe(
    name: String,
    board: String = "",
    puzzleFile: String? = null,
    block: MatchFlowHarness.(ForgeCardRepository) -> Unit,
) {
    val repo = ForgeCardRepository.open()
    val harness = MatchFlowHarness(cardRepositoryOverride = repo)
    val puzzle =
        if (puzzleFile != null) {
            val path = puzzleFile
            sequenceOf(File(path), File("..", path)).first { it.isFile }.readText()
        } else {
            """
            [metadata]
            Name:Forge catalog $name
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Exercise one GRE interaction using Forge-derived metadata.
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent() + "\n" + board
        }
    try {
        harness.connect(puzzleText = puzzle)
        harness.block(repo)
        println("FORGE_PROBE_PASS $name cards=${repo.findAllGrpIds().size} messages=${harness.allMessages.size}")
    } catch (error: Throwable) {
        harness.dumpDiagnostics(name)
        throw error
    } finally {
        val output = File("build/forge-catalog-probe").apply { mkdirs() }
        File(output, "$name.gre.json").writeText(harness.allMessages.joinToString(",", "[", "]") { JsonFormat.printer().print(it) })
        val cardIds =
            harness.allMessages
                .filter {
                    it.hasGameStateMessage()
                }.flatMap { it.gameStateMessage.gameObjectsList }
                .map { it.grpId }
                .toSet()
        val metadata =
            buildJsonObject {
                for (id in cardIds) {
                    val data = repo.findByGrpId(id)
                    val label = repo.findNameByGrpId(id) ?: continue
                    put(
                        id.toString(),
                        buildJsonObject {
                            put("name", label)
                            put("power", data?.power.orEmpty())
                            put("toughness", data?.toughness.orEmpty())
                            put(
                                "types",
                                data?.types.orEmpty().joinToString(" ") {
                                    CardType
                                        .forNumber(it)
                                        ?.name
                                        .orEmpty()
                                        .substringBefore('_')
                                },
                            )
                        },
                    )
                }
            }
        File(output, "$name.cards.json").writeText(metadata.toString())
        harness.shutdown()
    }
}

private val leyline.tooling.headless.PlayerZone.cards get() = player.getZone(zone).cards
