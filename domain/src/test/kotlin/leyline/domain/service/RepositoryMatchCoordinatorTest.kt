package leyline.domain.service

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.domain.CollationPool
import leyline.domain.Course
import leyline.domain.CourseDeck
import leyline.domain.CourseId
import leyline.domain.CourseModule
import leyline.domain.Deck
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.DraftSession
import leyline.domain.DraftSessionId
import leyline.domain.DraftStatus
import leyline.domain.Format
import leyline.domain.PlayerId
import leyline.domain.repo.CourseRepository
import leyline.domain.repo.DeckRepository
import leyline.domain.repo.DraftSessionRepository

private class FakeDraftRepo : DraftSessionRepository {
    private val sessions = mutableMapOf<DraftSessionId, DraftSession>()
    private val pods = mutableMapOf<DraftSessionId, List<List<Int>>>()

    override fun findById(id: DraftSessionId) = sessions[id]

    override fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ) = sessions.values.firstOrNull { it.playerId == playerId && it.eventName == eventName }

    override fun save(session: DraftSession) {
        sessions[session.id] = session
    }

    override fun delete(id: DraftSessionId) {
        sessions.remove(id)
        pods.remove(id)
    }

    override fun deleteIncomplete() {
        sessions.values
            .filter { it.status != DraftStatus.Completed }
            .map { it.id }
            .forEach {
                sessions.remove(it)
                pods.remove(it)
            }
    }

    override fun savePodResults(
        sessionId: DraftSessionId,
        botDecks: List<List<Int>>,
    ) {
        pods[sessionId] = botDecks.map { it.toList() }
    }

    override fun findPodResults(sessionId: DraftSessionId): List<List<Int>> = pods[sessionId] ?: emptyList()
}

private class FakeCourseRepo : CourseRepository {
    private val courses = mutableMapOf<CourseId, Course>()

    override fun findById(id: CourseId) = courses[id]

    override fun findByPlayer(playerId: PlayerId) = courses.values.filter { it.playerId == playerId }

    override fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ) = courses.values.firstOrNull { it.playerId == playerId && it.eventName == eventName }

    override fun save(course: Course) {
        courses[course.id] = course
    }

    override fun delete(id: CourseId) {
        courses.remove(id)
    }
}

private class FakeDeckRepo(
    private val decks: List<Deck> = emptyList(),
) : DeckRepository {
    override fun findById(id: DeckId): Deck? = decks.firstOrNull { it.id == id }

    override fun findByName(name: String): Deck? = decks.firstOrNull { it.name == name }

    override fun findAllForPlayer(playerId: PlayerId): List<Deck> = decks.filter { it.playerId == playerId }

    override fun save(deck: Deck) {}

    override fun delete(id: DeckId) {}
}

class RepositoryMatchCoordinatorTest :
    FunSpec({
        val playerId = PlayerId("test-player")
        val event = "QuickDraft_FDN_20260503"

        fun coordinator(
            draftRepo: FakeDraftRepo = FakeDraftRepo(),
            courseRepo: FakeCourseRepo = FakeCourseRepo(),
            deckRepo: FakeDeckRepo = FakeDeckRepo(),
        ): RepositoryMatchCoordinator {
            val courseService =
                CourseService(courseRepo) {
                    GeneratedPool(emptyList(), listOf(CollationPool(0, emptyList())), 0)
                }
            return RepositoryMatchCoordinator(playerId, deckRepo, courseService, draftRepo)
        }

        fun deck(
            id: String,
            name: String,
            grpId: Int,
            format: Format = Format.Standard,
        ): Deck =
            Deck(
                id = DeckId(id),
                playerId = playerId,
                name = name,
                format = format,
                tileId = 0,
                mainDeck = listOf(DeckCard(grpId, 4)),
                sideboard = emptyList(),
                commandZone = emptyList(),
                companions = emptyList(),
            )

        test("direct bot match results do not require or create a course") {
            val courseRepo = FakeCourseRepo()
            val coord = coordinator(courseRepo = courseRepo)

            for (botEvent in listOf("AIBotMatch", "AIBotMatch_Rebalanced")) {
                coord.selectEvent(botEvent)
                coord.reportMatchResult(won = false)
                coord.reportMatchResult(won = true)
            }

            courseRepo.findByPlayer(playerId) shouldBe emptyList()
        }

        test("direct bot result leaves an existing completed course unchanged") {
            val courseRepo = FakeCourseRepo()
            val course =
                Course(
                    id = CourseId("bot-course"),
                    playerId = playerId,
                    eventName = "AIBotMatch",
                    module = CourseModule.Complete,
                )
            courseRepo.save(course)
            val coord = coordinator(courseRepo = courseRepo)
            coord.selectEvent("AIBotMatch")

            coord.reportMatchResult(won = false)

            courseRepo.findById(course.id) shouldBe course
        }

        test("joined event results still advance the selected course") {
            val courseRepo = FakeCourseRepo()
            val course =
                Course(
                    id = CourseId("course"),
                    playerId = playerId,
                    eventName = event,
                    module = CourseModule.CreateMatch,
                )
            courseRepo.save(course)
            val coord = coordinator(courseRepo = courseRepo)
            coord.selectEvent(event)

            coord.reportMatchResult(won = false)
            coord.reportMatchResult(won = true)

            courseRepo.findById(course.id) shouldBe course.copy(wins = 1, losses = 1)
        }

        test("joined event result without a course still reports invalid lifecycle") {
            val coord = coordinator()
            coord.selectEvent(event)

            shouldThrow<IllegalArgumentException> { coord.reportMatchResult(won = false) }
                .message shouldBe "No course for $event"
        }

        test("resolveOpponentDeckCards returns null when no draft session") {
            coordinator().resolveOpponentDeckCards(event) shouldBe null
        }

        fun brawlDeck(id: String): Deck =
            Deck(
                id = DeckId(id),
                playerId = playerId,
                name = id,
                format = Format.Brawl,
                tileId = 0,
                mainDeck = listOf(DeckCard(101, 59)),
                sideboard = emptyList(),
                commandZone = listOf(DeckCard(301, 1)),
                companions = listOf(DeckCard(401, 1)),
            )

        test("every repository-backed fallback preserves command zone and companions") {
            val coord = coordinator(deckRepo = FakeDeckRepo(listOf(brawlDeck("brawl-1"), brawlDeck("brawl-2"))))
            coord.selectEvent("Play_Brawl")

            val fromFirst = coord.resolveFirstDeckCards()
            val fromName = coord.resolveDeckCardsByName("brawl-1")
            val fromRandomPair = coord.resolveRandomDeckCardsPair()

            assertSoftly {
                fromFirst.shouldNotBeNull()
                fromFirst.commandZone shouldBe listOf(DeckCard(301, 1))
                fromFirst.companions shouldBe listOf(DeckCard(401, 1))

                fromName.shouldNotBeNull()
                fromName.commandZone shouldBe listOf(DeckCard(301, 1))
                fromName.companions shouldBe listOf(DeckCard(401, 1))

                fromRandomPair.shouldNotBeNull()
                val (first, second) = fromRandomPair
                first.companions shouldBe listOf(DeckCard(401, 1))
                second.companions shouldBe listOf(DeckCard(401, 1))
            }
        }

        test("resolveDeckCardsByName random picks a non-selected player deck") {
            val coord =
                coordinator(
                    deckRepo =
                        FakeDeckRepo(
                            listOf(
                                deck("selected", "Selected", 101),
                                deck("other", "Other", 202),
                            ),
                        ),
                )
            coord.selectDeck("selected")

            val cards = coord.resolveDeckCardsByName("random")

            cards.shouldNotBeNull()
            cards.mainDeck shouldBe listOf(DeckCard(202, 4))
        }

        test("resolveDeckCardsByName random filters by selected event format") {
            val coord =
                coordinator(
                    deckRepo =
                        FakeDeckRepo(
                            listOf(
                                deck("standard", "Standard", 101),
                                deck("brawl", "Brawl", 202, Format.Brawl),
                            ),
                        ),
                )
            coord.selectEvent("Play_Brawl")

            val cards = coord.resolveDeckCardsByName("random")

            cards.shouldNotBeNull()
            cards.mainDeck shouldBe listOf(DeckCard(202, 4))
        }

        test("resolveRandomDeckCardsPair picks two distinct decks when possible") {
            val coord =
                coordinator(
                    deckRepo =
                        FakeDeckRepo(
                            listOf(
                                deck("one", "One", 101),
                                deck("two", "Two", 202),
                            ),
                        ),
                )

            val pair = coord.resolveRandomDeckCardsPair()

            pair.shouldNotBeNull()
            val (first, second) = pair
            setOf(first.mainDeck.single().grpId, second.mainDeck.single().grpId) shouldBe setOf(101, 202)
        }

        test("resolveOpponentDeckCards returns null when draft incomplete") {
            val draftRepo = FakeDraftRepo()
            draftRepo.save(
                DraftSession(
                    id = DraftSessionId("d1"),
                    playerId = playerId,
                    eventName = event,
                    status = DraftStatus.PickNext,
                    draftPack = listOf(1, 2, 3),
                    pickedCards = emptyList(),
                ),
            )
            coordinator(draftRepo).resolveOpponentDeckCards(event) shouldBe null
        }

        test("resolveOpponentDeckCards returns null when no pod persisted") {
            val draftRepo = FakeDraftRepo()
            draftRepo.save(
                DraftSession(
                    id = DraftSessionId("d1"),
                    playerId = playerId,
                    eventName = event,
                    status = DraftStatus.Completed,
                ),
            )
            coordinator(draftRepo).resolveOpponentDeckCards(event) shouldBe null
        }

        test("resolveOpponentDeckCards groups grpIds by quantity") {
            val draftRepo = FakeDraftRepo()
            val sessionId = DraftSessionId("d1")
            draftRepo.save(
                DraftSession(
                    id = sessionId,
                    playerId = playerId,
                    eventName = event,
                    status = DraftStatus.Completed,
                ),
            )
            val seat1Deck = listOf(100, 100, 100, 200, 200, 300)
            draftRepo.savePodResults(
                sessionId,
                listOf(seat1Deck) + List(6) { listOf(900 + it) },
            )

            val cards = coordinator(draftRepo).resolveOpponentDeckCards(event)
            cards.shouldNotBeNull()

            cards.mainDeck shouldBe listOf(DeckCard(100, 3), DeckCard(200, 2), DeckCard(300, 1))
        }

        test("resolveOpponentDeckCards rotates bot selection per match launch") {
            val draftRepo = FakeDraftRepo()
            val courseRepo = FakeCourseRepo()
            val sessionId = DraftSessionId("d1")
            draftRepo.save(
                DraftSession(
                    id = sessionId,
                    playerId = playerId,
                    eventName = event,
                    status = DraftStatus.Completed,
                ),
            )
            val pod = (0 until 7).map { seat -> listOf(700 + seat) }
            draftRepo.savePodResults(sessionId, pod)

            val coord = coordinator(draftRepo, courseRepo)
            coord.resolveOpponentDeckCards(event)!!.mainDeck shouldBe listOf(DeckCard(700, 1))
            coord.resolveOpponentDeckCards(event)!!.mainDeck shouldBe listOf(DeckCard(701, 1))
        }

        test("configureCourseMatch mirrors seat1 as the opponent when no draft pod exists") {
            val courseRepo = FakeCourseRepo()
            val sealedEvent = "Sealed_FDN_20260307"
            courseRepo.save(
                Course(
                    id = CourseId("c1"),
                    playerId = playerId,
                    eventName = sealedEvent,
                    module = CourseModule.CreateMatch,
                    deck = CourseDeck(DeckId("d1"), listOf(DeckCard(101, 23)), emptyList()),
                ),
            )

            val (seat1Cards, seat2Cards) = coordinator(courseRepo = courseRepo).configureCourseMatch(playerId, sealedEvent)

            seat1Cards shouldBe seat2Cards
        }

        test("configureCourseMatch uses the draft pod as opponent when one exists") {
            val draftRepo = FakeDraftRepo()
            val courseRepo = FakeCourseRepo()
            val sessionId = DraftSessionId("d1")
            draftRepo.save(DraftSession(id = sessionId, playerId = playerId, eventName = event, status = DraftStatus.Completed))
            draftRepo.savePodResults(sessionId, listOf(listOf(101)) + List(6) { listOf(900 + it) })
            courseRepo.save(
                Course(
                    id = CourseId("c1"),
                    playerId = playerId,
                    eventName = event,
                    module = CourseModule.CreateMatch,
                    deck = CourseDeck(DeckId("d1"), listOf(DeckCard(200, 40)), emptyList()),
                ),
            )

            val (seat1Cards, seat2Cards) = coordinator(draftRepo, courseRepo).configureCourseMatch(playerId, event)

            seat1Cards shouldNotBe seat2Cards
        }
    })
