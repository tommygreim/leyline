package leyline.native.account

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import leyline.native.NativeTag
import org.jetbrains.exposed.v1.jdbc.Database

private fun Application.testModule(
    store: AccountStore,
    tokens: TokenService,
    cachedManifests: String? = null,
) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    routing {
        accountRoutes(store, tokens, "localhost:30010", cachedManifests)
    }
}

class AccountRoutesTest :
    FunSpec({

        tags(NativeTag)

        fun testAppWithManifests(
            cachedManifests: String?,
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            val dbFile =
                java.io.File
                    .createTempFile("routes-test", ".db")
                    .also { it.deleteOnExit() }
            val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
            val store = AccountStore(db)
            store.createTables()
            val tokens = TokenService()

            // Seed a test account
            store.create("existing@test.com", "password123", "Existing")

            testApplication {
                application { testModule(store, tokens, cachedManifests) }
                block()
            }
        }

        fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
            testAppWithManifests(null, block)
        }

        suspend fun ApplicationTestBuilder.postToken(vararg fields: Pair<String, String>): HttpResponse =
            client.submitForm(
                url = "/auth/oauth/token",
                formParameters =
                    parameters {
                        fields.forEach { (name, value) -> append(name, value) }
                    },
            )

        test("login with valid credentials returns 200 + tokens") {
            testApp {
                val resp = postToken("grant_type" to "password", "username" to "existing@test.com", "password" to "password123")
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "access_token"
                body shouldContain "refresh_token"
                body shouldContain "persona_id"
            }
        }

        test("login with wrong password returns 401") {
            testApp {
                val resp = postToken("grant_type" to "password", "username" to "existing@test.com", "password" to "wrong")
                resp.status shouldBe HttpStatusCode.Unauthorized
                resp.bodyAsText() shouldContain "INVALID ACCOUNT CREDENTIALS"
            }
        }

        test("login with unknown email returns 401") {
            testApp {
                val resp = postToken("grant_type" to "password", "username" to "nobody@test.com", "password" to "pass")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("refresh token grant returns new tokens") {
            testApp {
                // First login to get a refresh token
                val loginResp =
                    postToken("grant_type" to "password", "username" to "existing@test.com", "password" to "password123")
                val refreshToken =
                    """"refresh_token":"([^"]+)""""
                        .toRegex()
                        .find(loginResp.bodyAsText())!!
                        .groupValues[1]

                val resp = postToken("grant_type" to "refresh_token", "refresh_token" to refreshToken)
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "access_token"
            }
        }

        test("register is disabled") {
            testApp {
                val resp =
                    client.post("/accounts/register") {
                        setBody(
                            """{"displayName":"NewPlayer","email":"new@test.com","password":"secret",""" +
                                """"country":"US","dateOfBirth":"1990-01-01","acceptedTC":true}""",
                        )
                        contentType(ContentType.Application.Json)
                    }
                resp.status shouldBe HttpStatusCode.Forbidden
                resp.bodyAsText() shouldContain "REGISTRATION DISABLED"
            }
        }

        test("profile with valid token returns account data") {
            testApp {
                // Login first
                val loginResp =
                    postToken("grant_type" to "password", "username" to "existing@test.com", "password" to "password123")
                val token =
                    """"access_token":"([^"]+)""""
                        .toRegex()
                        .find(loginResp.bodyAsText())!!
                        .groupValues[1]

                val resp =
                    client.get("/profile") {
                        header("Authorization", "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "accountID"
                body shouldContain "personaID"
                body shouldContain "existing@test.com"
                body shouldContain """"gameID":"arena""""
            }
        }

        test("profile without auth returns 401") {
            testApp {
                val resp = client.get("/profile")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("profile rejects unknown bearer") {
            testApp {
                val resp =
                    client.get("/profile") {
                        header("Authorization", "Bearer ll_fake")
                    }

                resp.status shouldBe HttpStatusCode.Unauthorized
                resp.bodyAsText() shouldContain "INVALID TOKEN"
            }
        }

        test("doorbell returns FdURI with empty manifests") {
            testApp {
                val resp =
                    client.post("/api/doorbell/api/v2/ring") {
                        setBody("{}")
                        contentType(ContentType.Application.Json)
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain """"FdURI":"localhost:30010""""
                body shouldContain """"BundleManifests":[]"""
            }
        }

        test("doorbell returns cached BundleManifests when available") {
            val manifests = """[{"category":"Audio","priority":50,"hash":"abc123"}]"""
            testAppWithManifests(manifests) {
                val resp =
                    client.post("/api/doorbell/api/v2/ring") {
                        setBody("{}")
                        contentType(ContentType.Application.Json)
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain """"FdURI":"localhost:30010""""
                body shouldContain """"category":"Audio""""
                body shouldContain """"hash":"abc123""""
            }
        }

        test("age gate stub returns false") {
            testApp {
                val resp =
                    client.post("/accounts/requires-age-gate") {
                        setBody("""{"Country":"US","DateOfBirth":"1990-01-01"}""")
                        contentType(ContentType.Application.Json)
                    }
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "false"
            }
        }

        test("moderate stub returns 200") {
            testApp {
                val resp =
                    client.post("/accounts/moderate") {
                        setBody("""{"value":"test","name":"Display Name"}""")
                        contentType(ContentType.Application.Json)
                    }
                resp.status shouldBe HttpStatusCode.OK
            }
        }

        test("skus stub returns empty items") {
            testApp {
                val resp = client.get("/xsollaconnector/client/skus")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain """"items":[]"""
            }
        }

        test("local social friends response contains an empty iterable list") {
            testApp {
                val resp = client.get("/friends/friendship/all")

                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldBe """{"friends":[]}"""
            }
        }

        test("local presence acknowledges the authenticated account and supplied status") {
            testApp {
                val login =
                    Json
                        .parseToJsonElement(
                            postToken(
                                "grant_type" to "password",
                                "username" to "existing@test.com",
                                "password" to "password123",
                            ).bodyAsText(),
                        ).jsonObject
                val token = login.getValue("access_token").jsonPrimitive.content
                val resp =
                    client.post("/presence/app-presence") {
                        header("Authorization", "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"platformStatus":3,"gameStatus":"Local","expiry":2000000000,"data":"e30="}""")
                    }
                val presence = Json.parseToJsonElement(resp.bodyAsText()).jsonObject

                assertSoftly {
                    resp.status shouldBe HttpStatusCode.OK
                    presence["accountId"] shouldBe login["account_id"]
                    presence["personaId"] shouldBe login["persona_id"]
                    presence["gameId"] shouldBe JsonPrimitive("arena")
                    presence["platformStatus"] shouldBe JsonPrimitive(3)
                    presence["gameStatus"] shouldBe JsonPrimitive("Local")
                    presence["expiry"] shouldBe JsonPrimitive(2000000000L)
                    presence["data"] shouldBe JsonPrimitive("e30=")
                    presence
                        .getValue("updatedAt")
                        .jsonPrimitive.isString
                        .shouldBeFalse()
                    presence.getValue("updatedAt").jsonPrimitive.long shouldBeGreaterThan 0L
                }
            }
        }

        test("local presence accepts an omitted expiry and nullable data") {
            testApp {
                val login =
                    Json
                        .parseToJsonElement(
                            postToken(
                                "grant_type" to "password",
                                "username" to "existing@test.com",
                                "password" to "password123",
                            ).bodyAsText(),
                        ).jsonObject
                val token = login.getValue("access_token").jsonPrimitive.content
                val resp =
                    client.post("/presence/app-presence") {
                        header("Authorization", "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"platformStatus":0,"gameStatus":"","expiry":null,"data":null}""")
                    }
                val presence = Json.parseToJsonElement(resp.bodyAsText()).jsonObject

                assertSoftly {
                    resp.status shouldBe HttpStatusCode.OK
                    presence["data"] shouldBe JsonNull
                    presence.getValue("expiry").jsonPrimitive.long shouldBeGreaterThan presence.getValue("updatedAt").jsonPrimitive.long
                }
            }
        }

        test("local presence rejects an unknown bearer token") {
            testApp {
                val resp =
                    client.post("/presence/app-presence") {
                        header("Authorization", "Bearer ll_fake")
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }

                resp.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("unknown path returns 200 with empty JSON") {
            testApp {
                val resp = client.get("/some/unknown/path")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldBe "{}"
            }
        }
    })
