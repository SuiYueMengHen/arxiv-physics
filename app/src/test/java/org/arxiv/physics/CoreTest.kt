package org.arxiv.physics

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun updatePolicyRejectsWrongOriginsAndDowngrades() {
        assertTrue(UpdatePolicy.newer(6, 5))
        assertFalse(UpdatePolicy.newer(5, 5))
        assertFalse(UpdatePolicy.newer(4, 5))
        assertTrue(UpdatePolicy.releaseAsset("https://github.com/SuiYueMengHen/arxiv-physics/releases/download/v0.5.0/app.apk"))
        assertFalse(UpdatePolicy.releaseAsset("https://github.com/attacker/arxiv-physics/releases/download/v0.5.0/app.apk"))
        assertFalse(UpdatePolicy.releaseAsset("https://github.com.evil.com/SuiYueMengHen/arxiv-physics/releases/download/v0.5.0/app.apk"))
        assertFalse(UpdatePolicy.releaseAsset("http://github.com/SuiYueMengHen/arxiv-physics/releases/download/v0.5.0/app.apk"))
        assertTrue(UpdatePolicy.validHash("a".repeat(64)))
        assertFalse(UpdatePolicy.validHash("z".repeat(64)))
    }
    @Test fun translatedTitleComesFromDocumentHeadingRatherThanSections() {
        assertEquals("量子自旋链", TranslationTitle.extract("# 量子自旋链\nSpin chains\n\n## 摘要\n正文"))
        assertEquals("时空中的量子纠缠", TranslationTitle.extract("**中文标题：时空中的量子纠缠**\n正文"))
        assertNull(TranslationTitle.extract("# Abstract\n## 这是章节标题\n# 摘要"))
        assertNull(TranslationTitle.extract("```\n# 这不是论文标题\n```\n## 引言"))
        assertTrue(TranslationProtocol.prompt(Paper("test", "Original", emptyList(), "", "", "", emptyList(), "")).contains("# 中文论文标题"))
    }

    @Test fun clipboardRequiresChangedTextLongerThan500Characters() {
        assertFalse(ClipboardImportPolicy.accepts("字".repeat(500), "old"))
        assertTrue(ClipboardImportPolicy.accepts("字".repeat(501), "old"))
        assertFalse(ClipboardImportPolicy.accepts("字".repeat(501), "字".repeat(501)))
        assertTrue(ClipboardImportPolicy.accepts("字".repeat(501), "字".repeat(501), freshCopy = true))
        assertFalse(ClipboardImportPolicy.accepts(null, "old"))
        assertFalse(ClipboardImportPolicy.accepts(" ".repeat(1000), "old"))
    }
    @Test fun clipboardCountsUnicodeCharactersRatherThanUtf16Units() {
        assertFalse(ClipboardImportPolicy.accepts("😀".repeat(300), "old"))
        assertTrue(ClipboardImportPolicy.accepts("😀".repeat(501), "old"))
    }
    @Test fun wholeSiteLatestQueryUsesBoundedDateRangeInsteadOfWildcard() {
        assertEquals("submittedDate:[202609040000 TO 202610042359]", Physics.latestAllQuery(java.time.LocalDate.of(2026, 10, 4)))
        assertFalse(Physics.latestAllQuery().contains("all:*"))
    }
    @Test fun taxonomyIncludesEveryPhysicsCategory() {
        assertEquals(51, Physics.categories.size)
        assertEquals(51, Physics.categories.map { it.id }.toSet().size)
        assertEquals(22, Physics.categories.count { it.id.startsWith("physics.") })
        assertEquals(9, Physics.categories.count { it.id.startsWith("cond-mat.") })
        assertEquals(6, Physics.categories.count { it.id.startsWith("astro-ph.") })
    }
    @Test fun searchUsesInclusiveUtcRangeAndEscapesUserInput() {
        val q = SearchSpec(setOf("quant-ph"), "spin \"chain\"", "2025-01-01", "2025-02-28").query()
        assertTrue(q.contains("all:\"spin \\\"chain\\\"\""))
        assertTrue(q.contains("submittedDate:[202501010000 TO 202502282359]"))
    }
    @Test(expected = IllegalArgumentException::class) fun reversedRangeIsRejected() {
        SearchSpec(setOf("quant-ph"), from = "2025-04-01", to = "2025-03-01").query()
    }
    @Test(expected = IllegalArgumentException::class) fun emptyCategorySelectionIsRejected() {
        SearchSpec(emptySet()).query()
    }
    @Test fun parsesNamespacedAtomPreservingVersionAndMetadata() {
        val xml = """
          <feed xmlns="http://www.w3.org/2005/Atom" xmlns:arxiv="http://arxiv.org/schemas/atom" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
           <opensearch:totalResults>42</opensearch:totalResults><entry>
            <id>http://arxiv.org/abs/2501.12345v2</id><title>Spin
            chains &amp; correlations</title><summary>A &lt; B</summary><author><name>Alice</name></author><author><name>Bob</name></author>
            <published>2025-01-02T12:00:00Z</published><updated>2025-02-01T00:00:00Z</updated>
            <category term="quant-ph"/><category term="cond-mat.str-el"/><arxiv:primary_category term="quant-ph"/>
            <arxiv:doi>10.123/example</arxiv:doi><arxiv:comment>12 pages</arxiv:comment>
           </entry></feed>
        """.trimIndent()
        val (papers, total) = AtomParser.parse(xml.byteInputStream())
        assertEquals(42, total)
        val p = papers.single()
        assertEquals("2501.12345v2", p.id)
        assertEquals("Spin chains & correlations", p.title)
        assertEquals(listOf("Alice", "Bob"), p.authors)
        assertEquals("A < B", p.summary)
        assertEquals("quant-ph", p.primary)
        assertEquals("10.123/example", p.doi)
    }
    @Test fun supportsLegacyPaperIds() {
        val (papers, _) = AtomParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>http://arxiv.org/abs/hep-th/9901001v1</id><title>Legacy</title></entry></feed>""".byteInputStream())
        assertEquals("hep-th_9901001v1", papers.single().fileKey)
    }
    @Test(expected = org.xml.sax.SAXParseException::class) fun xmlExternalEntityIsRejected() {
        AtomParser.parse("""<!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/passwd">]><feed>&secret;</feed>""".byteInputStream())
    }
    @Test fun truncatedTranslationNeverClaimsCompletion() {
        val body = "译文正文。".repeat(100)
        assertFalse(TranslationProtocol.complete(body))
        assertFalse(TranslationProtocol.complete("${TranslationProtocol.END}\n$body"))
        assertFalse(TranslationProtocol.complete(TranslationProtocol.END))
        assertTrue(TranslationProtocol.complete("$body\n${TranslationProtocol.END}\n"))
        assertEquals(body, TranslationProtocol.clean("$body\n${TranslationProtocol.END}"))
    }
}
