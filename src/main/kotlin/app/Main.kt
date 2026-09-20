package app

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var port = 8080
    var db = Path.of("proof-replay.db")
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--port" -> {
                require(index + 1 < args.size) { "--port requires a value" }
                port = args[index + 1].toInt()
                index += 2
            }
            "--db" -> {
                require(index + 1 < args.size) { "--db requires a value" }
                db = Path.of(args[index + 1])
                index += 2
            }
            else -> throw IllegalArgumentException("unknown argument: ${args[index]}")
        }
    }

    val store = EventStore(db)
    val service = AppService(store)
    Fixtures.available.forEach { name ->
        runCatching {
            val snapshot = service.jsonPublic.decodeFromString(
                Snapshot.serializer(),
                Fixtures.load(name),
            )
            service.importSnapshot(snapshot)
        }
    }

    val server = ApiServer(service, port)
    server.start()
    println("proof-replay listening on http://127.0.0.1:$port")
    println("event store: $db (${service.state.events.size} events replayed)")

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        store.close()
    })
}
