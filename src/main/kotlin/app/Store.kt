package app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

/**
 * SQLite 仅保存一张不可变事件表；所有读模型在启动时重放事件构建。
 * 同一事件在一个事务里写入 seq/payload，seq 单调，永不更新、永不删除。
 */
class EventStore(dbPath: Path) {
    private val connection: Connection

    init {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:$dbPath" }
        connection = dataSource.connection
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS events (
                    seq  INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    at   TEXT NOT NULL,
                    payload TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    fun allEvents(): List<StoredEvent> =
        connection.prepareStatement("SELECT seq, type, at, payload FROM events ORDER BY seq").use { ps ->
            ps.executeQuery().use { rs ->
                val events = ArrayList<StoredEvent>()
                while (rs.next()) {
                    events.add(
                        StoredEvent(
                            seq = rs.getLong(1),
                            type = rs.getString(2),
                            at = rs.getString(3),
                            payload = Json.parseToJsonElement(rs.getString(4)) as JsonObject,
                        )
                    )
                }
                events
            }
        }

    fun append(type: String, payload: JsonObject): StoredEvent {
        val at = Instant.now().toString()
        connection.autoCommit = false
        try {
            val payloadText = Json.encodeToString(JsonObject.serializer(), payload)
            val event = connection.prepareStatement(
                "INSERT INTO events(type, at, payload) VALUES (?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setString(1, type)
                ps.setString(2, at)
                ps.setString(3, payloadText)
                ps.executeUpdate()
                val seq = ps.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
                StoredEvent(seq, type, at, Json.parseToJsonElement(payloadText) as JsonObject)
            }
            connection.commit()
            return event
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun close() = connection.close()
}
