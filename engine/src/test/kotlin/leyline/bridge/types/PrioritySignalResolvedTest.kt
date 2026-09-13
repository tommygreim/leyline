package leyline.bridge.types

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PrioritySignalResolvedTest :
    FunSpec({

        tags(UnitTag)

        test("consumePromptResolved returns false before any mark") {
            val signal = PrioritySignal()
            signal.consumePromptResolved() shouldBe false
        }

        test("mark then consume returns true exactly once") {
            val signal = PrioritySignal()
            signal.markPromptResolved()
            signal.consumePromptResolved() shouldBe true
            signal.consumePromptResolved() shouldBe false
        }

        test("multiple marks still consume to true-once (idempotent set)") {
            val signal = PrioritySignal()
            signal.markPromptResolved()
            signal.markPromptResolved()
            signal.consumePromptResolved() shouldBe true
            signal.consumePromptResolved() shouldBe false
        }

        test("one engine publication wakes both connection observers") {
            val signal = PrioritySignal()
            val ready = CountDownLatch(2)
            val observers =
                (1..2).map {
                    CompletableFuture.supplyAsync {
                        ready.countDown()
                        signal.awaitSignal(2_000)
                    }
                }
            check(ready.await(2, TimeUnit.SECONDS)) { "Both observers must start" }
            signal.signal()
            observers.map { it.get(3, TimeUnit.SECONDS) } shouldBe listOf(true, true)
        }

        test("an observer cannot consume an already published signal for another observer") {
            val signal = PrioritySignal()
            signal.signal()
            assertSoftly {
                signal.awaitSignal(0) shouldBe true
                signal.awaitSignal(0) shouldBe false
                CompletableFuture.supplyAsync { signal.awaitSignal(0) }.get(2, TimeUnit.SECONDS) shouldBe true
            }
        }
    })
