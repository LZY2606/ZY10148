package app

import java.util.concurrent.atomic.AtomicBoolean

private fun loadFixture(name: String): Map<String, Any?> {
    val text = AppServer::class.java.getResourceAsStream("/fixtures/$name")?.bufferedReader()?.readText()
        ?: error("fixture $name not found on classpath")
    return Json.parseObject(text)
}

fun main(args: Array<String>) {
    var port = 8080
    var dbPath = "data/prover.db"
    var noBootstrap = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--port" -> { port = args.getOrNull(++i)?.toIntOrNull() ?: error("--port requires an integer") }
            "--db" -> { dbPath = args.getOrNull(++i) ?: error("--db requires a path") }
            "--no-bootstrap" -> noBootstrap = true
            else -> error("unknown argument: $a")
        }
        i++
    }

    val store = Store(dbPath)
    val bootstrapped = AtomicBoolean(store.listSnapshots().isNotEmpty())

    fun bootstrap(): Map<String, Any?>? {
        if (bootstrapped.get()) return null
        val v1 = SnapshotInput.parse(loadFixture("mini-arith-v1.json"))
        val v2 = SnapshotInput.parse(loadFixture("mini-arith-v2.json"))
        val (s1, c1) = store.importSnapshot(v1)
        val (s2, c2) = store.importSnapshot(v2)
        val mapping = mapOf("lemma_add_comm" to "lemma_sum_comm")
        val plan = store.createPlan(s1.id, s2.id, mapping)
        bootstrapped.set(true)
        return linkedMapOf(
            "imported" to listOf(
                linkedMapOf("id" to s1.id, "created" to c1),
                linkedMapOf("id" to s2.id, "created" to c2)
            ),
            "plan" to plan.toJson()
        )
    }

    if (!noBootstrap) bootstrap()

    val server = AppServer(store, port) { bootstrap() }
    server.start()
    println("Prover replay service listening on http://127.0.0.1:${server.boundPort}")
    println("Database: $dbPath")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(); store.close() })
    Thread.currentThread().join()
}
