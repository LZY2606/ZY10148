package app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class ApiServer(private val service: AppService, port: Int) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    init {
        server.createContext("/", ::handle)
        server.executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    fun start() = server.start()
    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (e: ApiException) {
            writeJson(
                exchange,
                e.status,
                JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive(e.message ?: ""))),
            )
        } catch (e: SnapshotValidationException) {
            writeJson(
                exchange,
                422,
                JsonObject(
                    mapOf(
                        "error" to kotlinx.serialization.json.JsonPrimitive(e.message ?: "invalid snapshot"),
                    )
                ),
            )
        } catch (e: Exception) {
            e.printStackTrace()
            writeJson(
                exchange,
                500,
                JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive(e.message ?: "internal error"))),
            )
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val method = exchange.requestMethod
        val body = if (method == "POST" || method == "PUT") {
            exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        } else ""

        when {
            path == "/" || path == "/index.html" -> {
                exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                val html = WebUi.indexHtml
                exchange.sendResponseHeaders(200, html.toByteArray(StandardCharsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(html.toByteArray(StandardCharsets.UTF_8)) }
            }

            method == "GET" && path == "/api/health" ->
                writeJson(exchange, 200, JsonObject(mapOf("status" to kotlinx.serialization.json.JsonPrimitive("ok"))))

            method == "POST" && path == "/api/snapshots" -> {
                val snapshot = json.decodeFromString(Snapshot.serializer(), body)
                val (record, created) = service.importSnapshot(snapshot)
                writeJson(exchange, if (created) 201 else 200, record)
            }

            method == "POST" && path == "/api/snapshots/load-fixture" -> {
                val request = json.decodeFromString(LoadFixtureRequest.serializer(), body)
                val text = Fixtures.load(request.resource)
                val snapshot = json.decodeFromString(Snapshot.serializer(), text)
                val (record, created) = service.importSnapshot(snapshot)
                writeJson(
                    exchange,
                    if (created) 201 else 200,
                    JsonObject(
                        mapOf(
                            "created" to kotlinx.serialization.json.JsonPrimitive(created),
                            "snapshot" to json.encodeToJsonElement(SnapshotRecord.serializer(), record),
                        )
                    ),
                )
            }

            method == "GET" && path == "/api/snapshots" -> {
                val library = query(exchange)["library"]
                writeJson(exchange, 200, service.snapshots(library))
            }

            method == "GET" && path.startsWith("/api/snapshots/") ->
                withFingerprint(path.removePrefix("/api/snapshots/"), exchange) { fingerprint, sub ->
                    when (sub) {
                        "" -> writeJson(exchange, 200, service.snapshot(fingerprint))
                        "/graph" -> writeJson(exchange, 200, service.graph(fingerprint))
                        "/external-axioms" ->
                            writeJson(exchange, 200, service.externalAxiomStatus(fingerprint))
                        else -> throw ApiException(404, "not found")
                    }
                }

            method == "POST" && path == "/api/plans" -> {
                val request = json.decodeFromString(PlanRequest.serializer(), body)
                writeJson(exchange, 201, service.createPlan(request))
            }

            method == "GET" && path == "/api/plans" ->
                writeJson(exchange, 200, service.state.plans.values.toList())

            path.startsWith("/api/plans/") -> {
                val rest = path.removePrefix("/api/plans/").split("/", limit = 2)
                val planId = rest[0]
                val sub = if (rest.size > 1) "/" + rest[1] else ""
                when {
                    method == "GET" && sub == "" -> {
                        val version = query(exchange)["version"]?.toIntOrNull()
                        writeJson(exchange, 200, service.planStatus(planId, version))
                    }
                    method == "POST" && sub == "/freeze" ->
                        writeJson(exchange, 200, service.freezePlan(planId, json.decodeFromString(FreezeRequest.serializer(), body)))
                    method == "POST" && sub == "/narrow" ->
                        writeJson(exchange, 201, service.narrowSlice(planId, json.decodeFromString(NarrowRequest.serializer(), body)))
                    method == "POST" && sub == "/batches" ->
                        writeJson(exchange, 201, service.submitBatch(planId, json.decodeFromString(BatchSubmitRequest.serializer(), body)))
                    method == "GET" && sub == "/batches" ->
                        writeJson(exchange, 200, service.batches(planId))
                    else -> throw ApiException(404, "not found")
                }
            }

            method == "POST" && path == "/api/audits" ->
                writeJson(exchange, 201, service.auditAxiom(json.decodeFromString(AuditRequest.serializer(), body)))

            method == "GET" && path == "/api/audits" ->
                writeJson(exchange, 200, service.audits(query(exchange)["snapshot"]))

            method == "GET" && path == "/api/events" ->
                writeJson(exchange, 200, service.state.events)

            else -> throw ApiException(404, "not found: $method $path")
        }
    }

    private inline fun withFingerprint(
        segment: String,
        exchange: HttpExchange,
        block: (String, String) -> Unit,
    ) {
        val parts = segment.split("/", limit = 2)
        val fingerprint = parts[0]
        val sub = if (parts.size > 1) "/" + parts[1] else ""
        block(fingerprint, sub)
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.query?.split("&")?.mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx < 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }?.toMap() ?: emptyMap()

    private inline fun <reified T> writeJson(exchange: HttpExchange, status: Int, value: T) {
        val text = json.encodeToString(value)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
