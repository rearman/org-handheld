package com.rearman.orgpalm.org

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OrgDocumentTest {
	@Test
	fun everyFixtureRoundTripsByteForByte() {
		for (name in Fixtures.INPUTS + Fixtures.CASES.map { "$it.expected.org" }) {
			val bytes = Fixtures.bytes(name)
			val out = OrgDocument.parse(String(bytes, Charsets.UTF_8)).render().toByteArray(Charsets.UTF_8)
			assertArrayEquals(name, bytes, out)
		}
	}

	@Test
	fun headingsNestUnderTheirParentsWithExactSpans() {
		val text = Fixtures.text("children.org")
		val doc = OrgDocument.parse(text)
		assertEquals(2, doc.headerEnd)
		assertEquals(1, doc.roots.size)
		val work = doc.roots[0]
		assertEquals(listOf(2, 2), work.children.map { it.level })
		val rebuild = work.children[0]
		assertEquals(listOf("*** Order gaskets", "*** DONE Drain oil"), rebuild.children.map { doc.lines[it.start].text })
		assertSame(rebuild, rebuild.children[1].parent)
		assertEquals(work.children[1].start, rebuild.subtreeEnd)
		assertEquals(rebuild.children[0].start, rebuild.end)
		assertEquals(text.length, work.subtreeEndOffset)
		assertTrue(text.substring(rebuild.startOffset, rebuild.endOffset).startsWith("** TODO Rebuild compressor 3\n"))
		assertTrue(text.substring(rebuild.startOffset, rebuild.endOffset).endsWith("Parts list below.\n"))
	}
}
