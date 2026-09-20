package app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ApiAcceptanceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var service: AppService
    private lateinit var server: ApiServer
    private lateinit var client: HttpClient
    private var port = 0

    @BeforeEach
    fun setUp() {
        service = TestHarness.newService()
        port = TestHarness.freePort()
        server = ApiServer(service, port)
        server.start()
        client = HttpClient.newHttpClient()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    private fun request(path: String, method: String = "GET", body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        if (body != null) builder.header("Content-Type", "application/json")
        val req = builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody()).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.arr(key: String) = this[key]!!.jsonArray

    private fun loadFixture(name: String): JsonObject {
        val res = request("/api/snapshots/load-fixture", "POST", """{"resource":"$name"}""")
        assertEquals(201, res.statusCode(), res.body())
        return json.parseToJsonElement(res.body()).jsonObject.obj("snapshot")
    }

    private fun fingerprint(snapshot: JsonObject) = snapshot["snapshotFingerprint"]!!.jsonPrimitive.content

    @Test
    fun `full workflow import diff plan replay freeze audit events`() {
        assertEquals(200, request("/api/health").statusCode())

        val v1 = loadFixture("arith-v1.json")
        val v2 = loadFixture("arith-v2.json")
        val fp1 = fingerprint(v1)
        val fp2 = fingerprint(v2)

        // idempotent re-import returns 200 and creates no extra snapshot
        val second = request("/api/snapshots/load-fixture", "POST", """{"resource":"arith-v2.json"}""")
        assertEquals(200, second.statusCode())
        val list = json.parseToJsonElement(request("/api/snapshots").body()).jsonArray
        assertEquals(2, list.size)

        // illegal lemma cycle fixture is rejected with 422 and must not be stored
        val bad = request("/api/snapshots/load-fixture", "POST", """{"resource":"bad-lemma-cycle.json"}""")
        assertEquals(422, bad.statusCode())
        assertTrue(bad.body().contains("undeclared cycle"))
        assertEquals(2, json.parseToJsonElement(request("/api/snapshots").body()).jsonArray.size)

        // graph view: legal recursive SCC even/odd present, no illegal SCC
        val graph = json.parseToJsonElement(request("/api/snapshots/$fp2/graph").body()).jsonObject
        val sccs = graph.arr("sccs").map { it.jsonObject }
        val cyclic = sccs.filter { scc -> scc.arr("ids").size > 1 }
        assertEquals(1, cyclic.size)
        assertTrue(cyclic.single()["legal"]!!.jsonPrimitive.content.toBoolean())

        // create plan with explicit rename mapping
        val planBody = """{"library":"tiny-arith","fromSnapshot":"$fp1","toSnapshot":"$fp2","renameMapping":{"add_zero":"add_zero_rw"}}"""
        val created = request("/api/plans", "POST", planBody)
        assertEquals(201, created.statusCode())
        val plan = json.parseToJsonElement(created.body()).jsonObject
        val planId = plan["planId"]!!.jsonPrimitive.content

        val status = json.parseToJsonElement(request("/api/plans/$planId?version=1").body()).jsonObject
        val entries = status.arr("entries").map { it.jsonObject }
        val byId = entries.associateBy { it["symbolId"]!!.jsonPrimitive.content }

        // axiom changed -> even_or_odd replays with DEPENDENCY despite unchanged lemma fingerprint
        assertEquals("DEPENDENCY", byId.getValue("even_or_odd")["reason"]!!.jsonPrimitive.content)
        // changed lemma content -> CONTENT
        assertEquals("CONTENT", byId.getValue("add_comm")["reason"]!!.jsonPrimitive.content)
        // brand new lemma -> MISSING_PROOF
        assertEquals("MISSING_PROOF", byId.getValue("add_assoc")["reason"]!!.jsonPrimitive.content)
        // renamed lemma itself did not change: it replays only because the changed
        // `add` definition is a dependency (DEPENDENCY), never due to the rename alone
        assertEquals("DEPENDENCY", byId.getValue("add_zero_rw")["reason"]!!.jsonPrimitive.content)
        // succ doc-only change never appears in changed seeds
        val changed = status.arr("changedSymbols").map { it.jsonPrimitive.content }.toSet()
        assertTrue("succ" !in changed)
        assertTrue("add" in changed && "peano_induction" in changed)

        val layers = entries.map { it["layer"]!!.jsonPrimitive.int }.distinct().sorted()
        assertTrue(layers == (0..layers.last()).toList())

        // batch submission: wrong plan version is rejected (results only commit to declared version)
        val layer0 = entries.filter { it["layer"]!!.jsonPrimitive.int == 0 }
        val goodResults = layer0.joinToString(",") { e ->
            val id = e["symbolId"]!!.jsonPrimitive.content
            val slice = e.arr("sliceDependencies").joinToString(",") { """"${it.jsonPrimitive.content}"""" }
            """{"symbolId":"$id","status":"PROVED",
               "usedDependencies":[$slice],
               "evidence":{"prover":"p","certificate":"c-$id","checksum":"h-$id","checkedAt":"2026-09-21T00:00:00Z"}}"""
        }
        val wrongVersion = request("/api/plans/$planId/batches", "POST",
            """{"planVersion":99,"layer":0,"results":[$goodResults]}""")
        assertEquals(404, wrongVersion.statusCode())

        // mismatched layer coverage -> rejected, nothing published
        val partial = request("/api/plans/$planId/batches", "POST",
            """{"planVersion":1,"layer":0,"results":[]}""")
        assertEquals(400, partial.statusCode())

        // evidence with wrong slice -> rejected, batch stays unpublished
        val firstEntry = layer0.first()
        val firstId = firstEntry["symbolId"]!!.jsonPrimitive.content
        val wrongSlice = request("/api/plans/$planId/batches", "POST",
            """{"planVersion":1,"layer":0,"results":[
              {"symbolId":"$firstId","status":"PROVED","usedDependencies":[],
               "evidence":{"prover":"p","certificate":"c","checksum":"h","checkedAt":"t"}},
              ${layer0.drop(1).joinToString(",") { e ->
                val id = e["symbolId"]!!.jsonPrimitive.content
                val slice = e.arr("sliceDependencies").joinToString(",") { """"${it.jsonPrimitive.content}"""" }
                """{"symbolId":"$id","status":"PROVED","usedDependencies":[$slice],
                    "evidence":{"prover":"p","certificate":"c-$id","checksum":"h-$id","checkedAt":"t"}}"""
            }}]}""")
        assertEquals(400, wrongSlice.statusCode())

        // successful layer-0 publication
        val ok = request("/api/plans/$planId/batches", "POST",
            """{"planVersion":1,"layer":0,"results":[$goodResults]}""")
        assertEquals(201, ok.statusCode())
        val publishedBatch = json.parseToJsonElement(ok.body()).jsonObject
        assertEquals(true, publishedBatch["published"]!!.jsonPrimitive.content.toBoolean())

        // republishing same layer conflicts
        val duplicate = request("/api/plans/$planId/batches", "POST",
            """{"planVersion":1,"layer":0,"results":[$goodResults]}""")
        assertEquals(409, duplicate.statusCode())

        // submit a FAILED layer (layer 1) so a theorem can be narrowed afterwards
        val layer1 = entries.filter { it["layer"]!!.jsonPrimitive.int == 1 }
        val failedResults = layer1.joinToString(",") { e ->
            val id = e["symbolId"]!!.jsonPrimitive.content
            val slice = e.arr("sliceDependencies").joinToString(",") { """"${it.jsonPrimitive.content}"""" }
            """{"symbolId":"$id","status":"FAILED",
               "usedDependencies":[$slice],
               "evidence":{"prover":"p","certificate":"fail-$id","checksum":"fh-$id","checkedAt":"2026-09-21T00:00:00Z",
                            "detail":"simulated failure"}}"""
        }
        val failed = request("/api/plans/$planId/batches", "POST",
            """{"planVersion":1,"layer":1,"results":[$failedResults]}""")
        assertEquals(201, failed.statusCode())

        // narrowing on a published-failed theorem creates version 2
        val failedId = layer1.first()["symbolId"]!!.jsonPrimitive.content
        val fullSlice = layer1.first().arr("sliceDependencies").map { it.jsonPrimitive.content }
        assertTrue(fullSlice.isNotEmpty())
        val narrowed = fullSlice.dropLast(1)
        val narrow = request("/api/plans/$planId/narrow", "POST",
            """{"planVersion":1,"symbolId":"$failedId","sliceDependencies":${JsonArray(narrowed.map { kotlinx.serialization.json.JsonPrimitive(it) })}}""")
        assertEquals(201, narrow.statusCode(), narrow.body())
        val v2plan = json.parseToJsonElement(narrow.body()).jsonObject
        assertEquals(2, v2plan["version"]!!.jsonPrimitive.int)
        val narrowedEntry = v2plan.arr("entries").map { it.jsonObject }
            .first { it["symbolId"]!!.jsonPrimitive.content == failedId }
        assertEquals(true, narrowedEntry["narrowed"]!!.jsonPrimitive.content.toBoolean())

        // slice must be a subset: widening is rejected
        val widen = request("/api/plans/$planId/narrow", "POST",
            """{"planVersion":2,"symbolId":"$failedId","sliceDependencies":${JsonArray(fullSlice.map { kotlinx.serialization.json.JsonPrimitive(it) })}}""")
        assertEquals(400, widen.statusCode())

        // external axiom audit happy path + non-external rejection
        val audit = request("/api/audits", "POST",
            """{"snapshotFingerprint":"$fp2","symbolId":"classic_excluded_middle",
               "status":"APPROVED","reviewer":"rev@x","note":"ok"}""")
        assertEquals(201, audit.statusCode())
        val nonExternal = request("/api/audits", "POST",
            """{"snapshotFingerprint":"$fp2","symbolId":"peano_induction",
               "status":"APPROVED","reviewer":"rev@x","note":""}""")
        assertEquals(400, nonExternal.statusCode())

        // freeze freezes one immutable version; refreeze conflicts
        val freeze = request("/api/plans/$planId/freeze", "POST", """{"planVersion":1,"note":"done"}""")
        assertEquals(200, freeze.statusCode())
        val refreeze = request("/api/plans/$planId/freeze", "POST", """{"planVersion":1,"note":""}""")
        assertEquals(409, refreeze.statusCode())
        // narrowing a frozen version is rejected
        val narrowFrozen = request("/api/plans/$planId/narrow", "POST",
            """{"planVersion":1,"symbolId":"$failedId","sliceDependencies":${JsonArray(narrowed.map { kotlinx.serialization.json.JsonPrimitive(it) })}}""")
        assertEquals(409, narrowFrozen.statusCode())

        // all decisions survived as append-only events, seq strictly increasing
        val events = json.parseToJsonElement(request("/api/events").body()).jsonArray
        val seqs = events.map { it.jsonObject["seq"]!!.jsonPrimitive.content.toLong() }
        assertEquals(seqs, seqs.sorted())
        assertEquals(seqs.size, seqs.distinct().size)
        val types = events.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertTrue(types.contains("SNAPSHOT_IMPORTED"))
        assertTrue(types.contains("PLAN_CREATED"))
        assertTrue(types.contains("BATCH_SUBMITTED"))
        assertTrue(types.contains("SLICE_NARROWED"))
        assertTrue(types.contains("AXIOM_AUDITED"))
        assertTrue(types.contains("PLAN_FROZEN"))
        // rejected attempt wrote no event: exactly 2 batch events (layer0 + layer1)
        assertEquals(2, types.count { it == "BATCH_SUBMITTED" })
    }
}
