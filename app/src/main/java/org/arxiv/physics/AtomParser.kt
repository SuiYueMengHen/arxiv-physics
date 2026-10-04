package org.arxiv.physics

import org.w3c.dom.Element
import java.io.InputStream
import java.io.ByteArrayOutputStream
import org.xml.sax.SAXParseException
import javax.xml.parsers.DocumentBuilderFactory

object AtomParser {
    fun parse(input: InputStream): Pair<List<Paper>, Int> {
        val buffer = ByteArray(8192)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 4 * 1024 * 1024) { "arXiv 响应过大" }
            bytes.write(buffer, 0, count)
        }
        val payload = bytes.toByteArray()
        require(payload.none { it == 0.toByte() }) { "arXiv 返回了非 UTF-8 文档" }
        // Android's XML factory does not support the Apache DOCTYPE feature.
        // Reject declarations before parsing and reject every external entity resolution.
        if (String(payload, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true))
            throw SAXParseException("不允许 XML DOCTYPE 声明", null)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXParseException("不允许外部 XML 实体", null) }
        val root = builder.parse(payload.inputStream()).documentElement
        fun Element.all(name: String): List<Element> {
            val nodes = getElementsByTagNameNS("*", name)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
        fun Element.value(name: String) = all(name).firstOrNull()?.textContent?.trim().orEmpty()
        val papers = root.all("entry").map { entry ->
            val id = entry.value("id").substringAfter("/abs/", "")
            require(id.matches(Regex("(?:\\d{4}\\.\\d{4,5}|[a-zA-Z.-]+/\\d{7})(?:v\\d+)?"))) { "arXiv 返回异常：${entry.value("summary")}" }
            Paper(id, entry.value("title").replace(Regex("\\s+"), " "),
                entry.all("author").map { it.value("name") }, entry.value("summary"),
                entry.value("published"), entry.value("updated"), entry.all("category").map { it.getAttribute("term") }.distinct(),
                entry.all("primary_category").firstOrNull()?.getAttribute("term").orEmpty(),
                entry.value("comment"), entry.value("journal_ref"), entry.value("doi"))
        }
        return papers to (root.value("totalResults").toIntOrNull() ?: papers.size)
    }
}
