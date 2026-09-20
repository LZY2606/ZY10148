package app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class AppServer(private val store: Store, val port: Int = 0, private val bootstrap: () -> Map<String, Any?>? = { null }) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val boundPort: Int get() = server.address.port

    init {
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (e: HttpError) {
                respond(exchange, e.status, linkedMapOf("error" to (e.message ?: "error")))
            } catch (e: BadRequest) {
                respond(exchange, 400, linkedMapOf("error" to (e.message ?: "bad request")))
            } catch (e: Throwable) {
                e.printStackTrace()
                respond(exchange, 500, linkedMapOf("error" to (e.message ?: e.javaClass.simpleName)))
            } finally {
                exchange.close()
            }
        }
        server.executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    fun start() = server.start()
    fun stop() = server.stop(0)

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val method = ex.requestMethod
        if (method == "GET" && path == "/") {
            html(ex); return
        }
        if (method == "GET" && path == "/health") {
            respond(ex, 200, linkedMapOf("ok" to true)); return
        }
        if (method == "GET" && path == "/api/events") {
            respond(ex, 200, linkedMapOf("events" to store.listEvents().map { it.toJson() })); return
        }
        if (method == "POST" && path == "/api/events/verify") {
            respond(ex, 200, linkedMapOf("valid" to store.verifyChain())); return
        }
        if (method == "GET" && path == "/api/snapshots") {
            respond(ex, 200, linkedMapOf("snapshots" to store.listSnapshots())); return
        }
        if (method == "POST" && path == "/api/snapshots/import") {
            val body = bodyJson(ex)
            val input = SnapshotInput.parse(J.o(body))
            val (snap, created) = store.importSnapshot(input)
            respond(ex, if (created) 201 else 200, linkedMapOf(
                "created" to created, "idempotent" to !created, "snapshot" to snapshotDetail(snap)
            ))
            return
        }
        val snapIdMatch = Regex("^/api/snapshots/(\\d+)$").matchEntire(path)
        if (method == "GET" && snapIdMatch != null) {
            val snap = store.getSnapshot(snapIdMatch.groupValues[1].toLong())
            respond(ex, 200, snapshotDetail(snap)); return
        }
        if (method == "GET" && path == "/api/plans") {
            respond(ex, 200, linkedMapOf("plans" to store.listPlans().map { it.toJson() })); return
        }
        if (method == "POST" && path == "/api/plans") {
            val body = J.o(bodyJson(ex))
            val mapping = parseMapping(body["mapping"])
            val plan = store.createPlan(
                J.intOpt(body, "oldSnapshotId")?.toLong() ?: throw BadRequest("oldSnapshotId required"),
                J.intOpt(body, "newSnapshotId")?.toLong() ?: throw BadRequest("newSnapshotId required"),
                mapping
            )
            respond(ex, 201, plan.toJson()); return
        }
        val planMatch = Regex("^/api/plans/([A-Za-z0-9]+)$").matchEntire(path)
        if (method == "GET" && planMatch != null) {
            respond(ex, 200, store.getPlanByPlanId(planMatch.groupValues[1]).toJson()); return
        }
        if (method == "POST" && path == "/api/demo/bootstrap") {
            val result = bootstrap() ?: throw HttpError(409, "demo already bootstrapped")
            respond(ex, 201, result); return
        }
        routePlanAction(ex, method, path)
        if (!ex.responseHeaders.containsKey("Content-type") && ex.responseCode == -1) {
            throw HttpError(404, "no route for $method $path")
        }
    }

    private fun routePlanAction(ex: HttpExchange, method: String, path: String) {
        val freeze = Regex("^/api/plans/([A-Za-z0-9]+)/freeze$").matchEntire(path)
        if (method == "POST" && freeze != null) {
            val body = J.o(bodyJson(ex))
            val plan = store.freezePlan(freeze.groupValues[1], J.intOpt(body, "planVersion"), J.strOpt(body, "note") ?: "")
            respond(ex, 200, plan.toJson()); return
        }
        val batches = Regex("^/api/plans/([A-Za-z0-9]+)/batches$").matchEntire(path)
        if (method == "GET" && batches != null) {
            respond(ex, 200, linkedMapOf("batches" to store.listBatches(batches.groupValues[1]))); return
        }
        val replay = Regex("^/api/plans/([A-Za-z0-9]+)/replay$").matchEntire(path)
        if (method == "POST" && replay != null) {
            val body = J.o(bodyJson(ex))
            val version = J.intOpt(body, "planVersion") ?: throw BadRequest("planVersion required (the exact plan version this replay targets)")
            val batch = J.intOpt(body, "batch") ?: throw BadRequest("batch required")
            val forced = J.strList(body, "forceFailures").toSet()
            val result = store.submitReplay(replay.groupValues[1], version, batch, forced, J.strOpt(body, "recordedBy") ?: "ci")
            respond(ex, if (result["published"] == true) 200 else 200, result); return
        }
        val reviews = Regex("^/api/plans/([A-Za-z0-9]+)/reviews$").matchEntire(path)
        if (method == "GET" && reviews != null) {
            respond(ex, 200, linkedMapOf("reviews" to store.listReviews(reviews.groupValues[1]).map { it.toJson() })); return
        }
        if (method == "POST" && reviews != null) {
            val body = J.o(bodyJson(ex))
            val row = store.reviewAxiom(
                reviews.groupValues[1],
                J.str(body, "axiomFqn"),
                J.str(body, "status"),
                J.strOpt(body, "reviewer") ?: "unknown",
                J.strOpt(body, "note") ?: ""
            )
            respond(ex, 201, row.toJson()); return
        }
        val slices = Regex("^/api/plans/([A-Za-z0-9]+)/slices$").matchEntire(path)
        if (method == "GET" && slices != null) {
            respond(ex, 200, linkedMapOf("slices" to store.listSlices(slices.groupValues[1]).map { it.toJson() })); return
        }
        if (method == "POST" && slices != null) {
            val body = J.o(bodyJson(ex))
            val row = store.narrowSlice(
                slices.groupValues[1], J.str(body, "lemmaFqn"), J.strOpt(body, "note") ?: ""
            )
            respond(ex, 201, row.toJson()); return
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMapping(v: Any?): Map<String, String> {
        if (v == null) return emptyMap()
        if (v is Map<*, *>) return v.entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
        if (v is List<*>) return v.associate {
            val m = it as? Map<String, Any?> ?: throw BadRequest("mapping entries must be objects {old,new}")
            J.str(m, "old") to J.str(m, "new")
        }
        throw BadRequest("mapping must be an object or list")
    }

    private fun snapshotDetail(snap: Snapshot): Map<String, Any?> {
        val graph = Graph(snap.symbols)
        val sccs = graph.sccs()
        return linkedMapOf(
            "id" to snap.id,
            "library" to snap.library,
            "libraryVersion" to snap.libraryVersion,
            "snapshotFingerprint" to snap.snapshotFingerprint,
            "importedAt" to snap.importedAt,
            "graph" to graph.toJson(),
            "sccs" to sccs.map { it.toJson() },
            "illegalSccs" to sccs.filter { !it.legalRecursion }.map { it.toJson() },
            "symbols" to snap.symbols.map { it.toJson() }
        )
    }

    private fun bodyJson(ex: HttpExchange): Any? {
        val text = ex.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
        if (text.isBlank()) throw BadRequest("empty JSON body")
        return Json.parse(text)
    }

    private fun respond(ex: HttpExchange, status: Int, value: Any?) {
        val bytes = Json.write(value, indent = false).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun html(ex: HttpExchange) {
        val bytes = AppServer::class.java.getResourceAsStream("/web/index.html")?.readAllBytes()
            ?: throw HttpError(500, "web/index.html missing")
        ex.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
