package com.rearman.orgpalm.org

import org.junit.Assert.assertEquals
import org.junit.Test

// The escaping rules of DESIGN.md section 4 item 6, each written and undone.
class EscapeTest {
	private val keywords = TodoKeywords.DEFAULT.all
	private val z = Escape.ZWSP

	@Test
	fun titleEndingInTagsGetsATrailingZeroWidthSpace() {
		assertEquals("Check the list :hot:$z", Escape.title("Check the list :hot:", keywords))
		assertEquals("Check the list :hot:", Escape.unTitle("Check the list :hot:$z", keywords))
		assertEquals("Ratio 3:1 at 5:00", Escape.title("Ratio 3:1 at 5:00", keywords))
		// Org reads no tags from the escaped title (make-fixtures.el checks it), and the writer matches.
		val written = Fixtures.writer().apply(
			Fixtures.text("todo.org"), FileKind.TODO,
			Edit.Update(Fixtures.IO_LIST, listOf(FieldChange.Title("Check the list :hot:"))),
		)
		val text = Fixtures.text("escape-title.expected.org")
		assertEquals(WriteResult.Written(text, Fixtures.IO_LIST), written)
		val task = OrgFile.parse(FileKind.TODO, text).tasks.single { it.id == Fixtures.IO_LIST }
		assertEquals("Check the list :hot:", task.title)
		assertEquals(emptyList<String>(), task.tags)
	}

	@Test
	fun titleStartingWithAKeywordCommentOrCookieGetsALeadingZeroWidthSpace() {
		for (t in listOf("TODO list review", "DONE", "COMMENT out", "[#A] first")) {
			assertEquals("$z$t", Escape.title(t, keywords))
			assertEquals(t, Escape.unTitle("$z$t", keywords))
		}
		assertEquals("TODOS", Escape.title("TODOS", keywords))
		val written = Fixtures.writer().apply(
			Fixtures.text("todo.org"), FileKind.TODO,
			Edit.Update(Fixtures.IO_LIST, listOf(FieldChange.Priority(null), FieldChange.State(null), FieldChange.Title("TODO list review"))),
		)
		val text = Fixtures.text("escape-keyword.expected.org")
		assertEquals(WriteResult.Written(text, Fixtures.IO_LIST), written)
		val task = OrgFile.parse(FileKind.TODO, text).tasks.single { it.id == Fixtures.IO_LIST }
		assertEquals(null, task.state)
		assertEquals("TODO list review", task.title)
	}

	@Test
	fun textThatLooksEscapedReadsBackAsTyped() {
		for (t in listOf("${z}TODO x", "a :b:$z", "$z$z")) assertEquals(t, Escape.unTitle(Escape.title(t, keywords), keywords))
		for (line in listOf(",* x", ",,:END:", ",plain", "  ,DEADLINE: soon")) assertEquals(line, Escape.unBodyLine(Escape.bodyLine(line)))
	}

	@Test
	fun bodyLinesThatReadAsStructureGetAComma() {
		val escaped = mapOf(
			"* Not a heading" to ",* Not a heading",
			"** Nor this" to ",** Nor this",
			":PROPERTIES:" to ",:PROPERTIES:",
			"  :END:" to "  ,:END:",
			"DEADLINE: <2026-11-25 Wed>" to ",DEADLINE: <2026-11-25 Wed>",
			"SCHEDULED: tomorrow" to ",SCHEDULED: tomorrow",
			"CLOSED: [2026-11-20 Fri 16:42]" to ",CLOSED: [2026-11-20 Fri 16:42]",
			"#+TODO: A | B" to ",#+TODO: A | B",
			",* typed w/ a comma" to ",,* typed w/ a comma",
		)
		for ((line, out) in escaped) {
			assertEquals(out, Escape.bodyLine(line))
			assertEquals(line, Escape.unBodyLine(out))
		}
		for (line in listOf("plain", " * an indented list item", "*bold*", ",not structure")) {
			assertEquals(line, Escape.bodyLine(line))
			assertEquals(line, Escape.unBodyLine(line))
		}
		// Through the writer and the reader: the record keeps its body and the file its headings.
		val body = "* Not a heading\n:END:\nDEADLINE: <2026-12-01 Tue>"
		val text = (Fixtures.writer().apply(Fixtures.text("todo.org"), FileKind.TODO, Edit.Update(Fixtures.VFD, listOf(FieldChange.Body(body)))) as WriteResult.Written).text
		val file = OrgFile.parse(FileKind.TODO, text)
		assertEquals(body, file.tasks.single { it.id == Fixtures.VFD }.body)
		assertEquals(OrgDocument.parse(Fixtures.text("todo.org")).headings.size, file.document.headings.size)
	}

	@Test
	fun taskBodyLineBeginningWithATimestampStaysAsTyped() {
		assertEquals("<2026-11-23 Mon> call the rep", Escape.bodyLine("<2026-11-23 Mon> call the rep"))
	}

	// In an event body, a line that begins w/ a timestamp would read as a timestamp line, so it takes a comma.
	@Test
	fun eventBodyLineBeginningWithATimestampGetsAComma() {
		for ((line, out) in mapOf(
			"<2026-11-30 Mon> call the rep" to ",<2026-11-30 Mon> call the rep",
			"[2026-11-20 Fri] noted" to ",[2026-11-20 Fri] noted",
			"  <%%(diary-float t 3 3)>" to "  ,<%%(diary-float t 3 3)>",
			",<2026-11-30 Mon>" to ",,<2026-11-30 Mon>",
		)) {
			assertEquals(out, Escape.bodyLine(line, timestamps = true))
			assertEquals(line, Escape.unBodyLine(out, timestamps = true))
		}
		val body = "<2026-11-30 Mon> call the rep\nThen a note\n[2026-11-20 Fri] noted"
		val written = Fixtures.writer().apply(
			Fixtures.text("calendar.org"), FileKind.CALENDAR,
			Edit.Update(Fixtures.PM, listOf(FieldChange.Body(body))),
		) as WriteResult.Written
		val pm = OrgFile.parse(FileKind.CALENDAR, written.text).events.single { it.id == Fixtures.PM }
		assertEquals(body, pm.body)
		assertEquals(listOf(Fixtures.ts("<2026-11-23 Mon 09:00-10:00>")), pm.timestamps)
		assertEquals(false, pm.readOnly)
	}

	@Test
	fun memoWithABlankFirstLineIsUntitled() {
		assertEquals(Escape.UNTITLED to "\nSecond line", Escape.memo("\nSecond line"))
		assertEquals("Title" to "Body\nMore", Escape.memo("Title\nBody\nMore"))
	}
}
