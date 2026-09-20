package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HttpAcceptanceTest {
    private val client = HttpClient.newHttpClient()

    private fun startDb(): Pair<Store, AppServer> {
        val store = Store(":memory:")
        val server = AppServer(store, 0)
        server.start()
        return store to server
    }

    private fun req(port: Int, path: String, method: String = "GET", body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        if (body != null) b.header("Content-Type", "application/json")
        val r = if (body != null) b.method(method, HttpRequest.BodyPublishers.ofString(body)) else b.method(method, HttpRequest.BodyPublishers.noBody())
        return client.send(r.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun fixtureText(name: String) =
        HttpAcceptanceTest::class.java.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    @Test
    fun `full http flow - import plan replay freeze and immutable events`() {
        val (store, server) = startDb()
        try {
            val port = server.boundPort
            assertEquals(200, req(port, "/health").statusCode())
            assertTrue(req(port, "/").body().contains("证明库重放控制台"))

            val imp1 = req(port, "/api/snapshots/import", "POST", fixtureText("mini-arith-v1.json"))
            assertEquals(201, imp1.statusCode(), imp1.body())
            val imp2 = req(port, "/api/snapshots/import", "POST", fixtureText("mini-arith-v1.json"))
            assertEquals(200, imp2.statusCode())
            assertEquals(true, Json.parseObject(imp2.body())["idempotent"])

            // import v2 and create plan via listing
            val i2 = req(port, "/api/snapshots/import", "POST", fixtureText("mini-arith-v2.json"))
            assertEquals(201, i2.statusCode())
            val id1 = (Json.parseObject(imp1.body())["snapshot"] as Map<*, *>)["id"] as Number
            val id2 = (Json.parseObject(i2.body())["snapshot"] as Map<*, *>)["id"] as Number
            val planResp = req(port, "/api/plans", "POST",
                """{"oldSnapshotId":$id1,"newSnapshotId":$id2,"mapping":{"lemma_add_comm":"lemma_sum_comm"}}""")
            assertEquals(201, planResp.statusCode(), planResp.body())
            val plan = Json.parseObject(planResp.body())
            val pid = plan["planId"] as String
            val ver = (plan["planVersion"] as Number).toInt()

            // wrong declared version rejected
            val bad = req(port, "/api/plans/$pid/replay", "POST",
                """{"planVersion":${ver + 1},"batch":0}""")
            assertEquals(409, bad.statusCode())

            val ok = req(port, "/api/plans/$pid/replay", "POST",
                """{"planVersion":$ver,"batch":0,"recordedBy":"acceptance"}""")
            assertEquals(200, ok.statusCode(), ok.body())
            assertEquals("PUBLISHED", Json.parseObject(ok.body())["status"])

            // freeze then replay locked
            req(port, "/api/plans/$pid/freeze", "POST", """{"planVersion":$ver}""")
            val locked = req(port, "/api/plans/$pid/replay", "POST",
                """{"planVersion":$ver,"batch":1}""")
            assertEquals(409, locked.statusCode())

            // external axiom review retained
            val rev = req(port, "/api/plans/$pid/reviews", "POST",
                """{"axiomFqn":"ax_omit_ext","status":"APPROVED","reviewer":"carol"}""")
            assertEquals(201, rev.statusCode(), rev.body())

            val events = Json.parseObject(req(port, "/api/events").body())
            val types = (events["events"] as List<*>).map { (it as Map<*, *>)["eventType"] }
            assertTrue(types.contains("SNAPSHOT_IMPORTED"))
            assertTrue(types.contains("PLAN_CREATED"))
            assertTrue(types.contains("REPLAY_PUBLISHED"))
            assertTrue(types.contains("PLAN_FROZEN"))
            assertTrue(types.contains("EXTERNAL_AXIOM_REVIEWED"))
            assertEquals(200, req(port, "/api/events/verify", "POST").statusCode())
        } finally {
            server.stop(); store.close()
        }
    }

    @Test
    fun `illegal cycle snapshot rejected over http with 422 and retained event`() {
        val (store, server) = startDb()
        try {
            val port = server.boundPort
            val r = req(port, "/api/snapshots/import", "POST", fixtureText("illegal-cycle.json"))
            assertEquals(422, r.statusCode(), r.body())
            val events = Json.parseObject(req(port, "/api/events").body())
            assertEquals("SNAPSHOT_REJECTED", ((events["events"] as List<*>).last() as Map<*, *>)["eventType"])
        } finally {
            server.stop(); store.close()
        }
    }

    @Test
    fun `mapping to unknown symbol is a 400`() {
        val (store, server) = startDb()
        try {
            val port = server.boundPort
            req(port, "/api/snapshots/import", "POST", fixtureText("mini-arith-v1.json"))
            req(port, "/api/snapshots/import", "POST", fixtureText("mini-arith-v2.json"))
            val snaps = Json.parseObject(req(port, "/api/snapshots").body())["snapshots"] as List<*>
            val ids = snaps.map { (it as Map<*, *>)["id"] as Number }
            val r = req(port, "/api/plans", "POST",
                """{"oldSnapshotId":${ids[0]},"newSnapshotId":${ids[1]},"mapping":{"nope":"also-nope"}}""")
            assertEquals(400, r.statusCode())
        } finally {
            server.stop(); store.close()
        }
    }
}
