package org.arxiv.physics

import java.time.LocalDate

data class Category(val id: String, val name: String, val group: String)

object Physics {
    fun latestAllQuery(today: LocalDate = LocalDate.now(java.time.ZoneOffset.UTC)) =
        "submittedDate:[${today.minusDays(30).toString().replace("-", "")}0000 TO ${today.toString().replace("-", "")}2359]"
    val categories = buildList {
        fun group(name: String, vararg items: Pair<String, String>) {
            items.forEach { add(Category(it.first, it.second, name)) }
        }
        group("天体物理", "astro-ph.CO" to "宇宙学与河外天体", "astro-ph.EP" to "地球与行星天体物理",
            "astro-ph.GA" to "星系天体物理", "astro-ph.HE" to "高能天体物理", "astro-ph.IM" to "仪器与方法", "astro-ph.SR" to "太阳与恒星天体物理")
        group("凝聚态", "cond-mat.dis-nn" to "无序系统与神经网络", "cond-mat.mes-hall" to "介观系统与量子霍尔效应",
            "cond-mat.mtrl-sci" to "材料科学", "cond-mat.other" to "其他凝聚态", "cond-mat.quant-gas" to "量子气体",
            "cond-mat.soft" to "软物质", "cond-mat.stat-mech" to "统计力学", "cond-mat.str-el" to "强关联电子", "cond-mat.supr-con" to "超导")
        group("基础与高能物理", "gr-qc" to "广义相对论与量子宇宙学", "hep-ex" to "高能实验", "hep-lat" to "高能格点",
            "hep-ph" to "高能唯象", "hep-th" to "高能理论", "math-ph" to "数学物理", "quant-ph" to "量子物理")
        group("非线性科学", "nlin.AO" to "适应与自组织", "nlin.CD" to "混沌动力学", "nlin.CG" to "元胞自动机与格子气体",
            "nlin.PS" to "模式形成与孤子", "nlin.SI" to "可积与精确可解系统")
        group("核物理", "nucl-ex" to "核实验", "nucl-th" to "核理论")
        group("物理学", "physics.acc-ph" to "加速器物理", "physics.ao-ph" to "大气与海洋物理", "physics.app-ph" to "应用物理",
            "physics.atm-clus" to "原子与分子团簇", "physics.atom-ph" to "原子物理", "physics.bio-ph" to "生物物理",
            "physics.chem-ph" to "化学物理", "physics.class-ph" to "经典物理", "physics.comp-ph" to "计算物理",
            "physics.data-an" to "数据分析、统计与概率", "physics.ed-ph" to "物理教育", "physics.flu-dyn" to "流体动力学",
            "physics.gen-ph" to "普通物理", "physics.geo-ph" to "地球物理", "physics.hist-ph" to "物理史与哲学",
            "physics.ins-det" to "仪器与探测器", "physics.med-ph" to "医学物理", "physics.optics" to "光学",
            "physics.plasm-ph" to "等离子体物理", "physics.pop-ph" to "科普物理", "physics.soc-ph" to "物理与社会", "physics.space-ph" to "空间物理")
    }
    fun query(ids: Collection<String>) = ids.sorted().joinToString(" OR ", "(", ")") { "cat:$it" }
}

data class Paper(
    val id: String, val title: String, val authors: List<String>, val summary: String,
    val published: String, val updated: String, val categories: List<String>,
    val primary: String, val comment: String = "", val journal: String = "", val doi: String = ""
) {
    val pdfUrl get() = "https://arxiv.org/pdf/$id"
    val absUrl get() = "https://arxiv.org/abs/$id"
    val fileKey get() = id.replace('/', '_')
}

data class Feed(val papers: List<Paper>, val total: Int, val fetched: Long, val stale: Boolean = false)
data class SearchSpec(val categories: Set<String>, val text: String = "", val from: String = "", val to: String = "", val ascending: Boolean = false) {
    fun query(): String {
        require(categories.isNotEmpty()) { "请至少选择一个物理分类" }
        val parts = mutableListOf(Physics.query(categories))
        if (text.isNotBlank()) {
            val escaped = text.trim().replace("\\", "\\\\").replace("\"", "\\\"")
            parts.add("all:\"$escaped\"")
        }
        if (from.isNotBlank() || to.isNotBlank()) {
            val start = if (from.isBlank()) LocalDate.of(1991, 1, 1) else LocalDate.parse(from)
            val end = if (to.isBlank()) LocalDate.now(java.time.ZoneOffset.UTC) else LocalDate.parse(to)
            require(!start.isAfter(end)) { "开始日期不能晚于结束日期" }
            parts.add("submittedDate:[${start.toString().replace("-", "")}0000 TO ${end.toString().replace("-", "")}2359]")
        }
        return parts.joinToString(" AND ")
    }
}

enum class Stage(val label: String) {
    QUEUED("等待中"), DOWNLOADING("下载 PDF"), UPLOADING("上传 PDF"), TRANSLATING("正在翻译"),
    PARTIAL("译文未完整，需继续"), DOWNLOADED("PDF 已下载"), COMPLETE("翻译完成"), FAILED("需要处理"), CANCELLED("已取消"), INTERRUPTED("任务已中断，可重试")
}
data class TranslationJob(val id: String, val stage: Stage, val progress: Int = 0, val message: String = "", val updated: Long = System.currentTimeMillis())

object TranslationProtocol {
    const val END = "ARXIV_TRANSLATION_COMPLETE"
    fun prompt(paper: Paper) = """
        请把附件中的论文完整翻译成简体中文，不要总结或省略。按原文顺序保留所有章节、公式、图表说明、附录和参考文献。
        用 Markdown 输出，首行是「# 中文论文标题」，下一行保留英文原题；公式保留 LaTeX 格式。直接开始翻译，尽量一次译完；如果长度受限，请在末尾说明译到哪里，我会让你继续。
    """.trimIndent()
    fun continuation(paper: Paper) = "请从上一条回答的结尾继续翻译附件论文，不重复、不省略，保持 Markdown 和 LaTeX 格式，直到全文译完。"
    fun complete(markdown: String) = markdown.trimEnd().endsWith(END) && markdown.substringBeforeLast(END).trim().length > 200
    fun clean(markdown: String) = markdown.trim().removeSuffix(END).trim().let {
        if (it.startsWith("```markdown\n") && it.endsWith("```")) it.removePrefix("```markdown\n").removeSuffix("```").trim() else it
    }
}
