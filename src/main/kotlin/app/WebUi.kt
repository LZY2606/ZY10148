package app

/** 单页界面从 classpath:/web/index.html 读取。 */
object WebUi {
    val indexHtml: String by lazy {
        val stream = javaClass.getResourceAsStream("/web/index.html")
            ?: error("web/index.html missing from classpath")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
