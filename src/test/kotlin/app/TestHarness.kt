package app

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

object TestHarness {
    fun newService(): AppService {
        val db: Path = Files.createTempFile("proof-replay-test", ".db")
        Files.deleteIfExists(db)
        db.toFile().deleteOnExit()
        return AppService(EventStore(db))
    }

    fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
