package app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * 固定的小型证明库 fixture。
 * 优先读取工作目录下 ./fixtures，便于演示时浏览原始 JSON；
 * 打包后回退到 classpath:/fixtures。
 */
object Fixtures {
    private val allowed = Regex("[A-Za-z0-9._/-]+")

    fun load(resource: String): String {
        val clean = resource.removePrefix("/").removePrefix("fixtures/")
        require(allowed.matches(clean) && !clean.contains("..")) { "illegal fixture name: $resource" }
        val local = Path.of("fixtures/$clean")
        if (local.exists()) return Files.readString(local)
        val stream = javaClass.getResourceAsStream("/fixtures/$clean")
            ?: throw ApiException(404, "fixture not found: $resource")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    val available: List<String>
        get() = listOf(
            "arith-v1.json",
            "arith-v2.json",
            "bad-lemma-cycle.json",
        )
}
