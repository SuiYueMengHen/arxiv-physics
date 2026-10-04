package org.arxiv.physics

/** Derives the translated title from the saved Markdown, preserving the original metadata. */
object TranslationTitle {
    fun extract(markdown: String): String? {
        var fenced = false
        for (raw in markdown.take(12000).lineSequence().take(100)) {
            val line = raw.trim()
            if (line.startsWith("```")) { fenced = !fenced; continue }
            if (fenced) continue
            // Only a top-level document title, never an abstract/section heading.
            val title = when {
                line.startsWith("# ") -> line.removePrefix("# ")
                line.startsWith("**") && line.endsWith("**") -> line.removeSurrounding("**")
                else -> continue
            }.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1").trim()
                .removePrefix("标题：").removePrefix("中文标题：").trim()
            if (title.length in 2..250 && title.any { it in '\u3400'..'\u9fff' } &&
                title !in setOf("摘要", "引言", "目录", "参考文献", "中文译文", "论文翻译", "完整翻译", "译文", "正文")) return title
        }
        return null
    }
}

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("白天 · 浅色"), DARK("夜间 · 深色") }
