package com.rearman.orgpalm.org

import org.junit.Assert.assertEquals
import org.junit.Test

// The escaping rules of DESIGN.md section 4 rule 6: a zero-width space only where Org would read
// structure, and naught undone on read.
class EscapeTest {
	private val keywords = TodoKeywords.DEFAULT.all
	private val z = Escape.ZWSP

	// The text of fixture [input] after an update of the heading whose :ID: is [id].
	private fun edit(input: String, id: String, vararg changes: FieldChange): String {
		val text = Fixtures.text(input)
		val result = Fixtures.writer().apply(text, Edit.Update(Fixtures.anchor(text, id), changes.toList()))
		return (result as WriteResult.Written).text
	}

	// Org reads no tags from the escaped title (make-fixtures.el checks it), and the title reads back
	// w/ the escape, as Org holds it.
	@Test
	fun titleEndingInTagsGetsATrailingZeroWidthSpace() {
		assertEquals("Check the list :hot:$z", Escape.title("Check the list :hot:", keywords))
		assertEquals("Ratio 3:1 at 5:00", Escape.title("Ratio 3:1 at 5:00", keywords))
		val text = Fixtures.text("escape-title.expected.org")
		assertEquals(text, edit("todo.org", Fixtures.IO_LIST, FieldChange.Title("Check the list :hot:")))
		val task = Fixtures.entry(text, Fixtures.IO_LIST)
		assertEquals("Check the list :hot:$z", task.title)
		assertEquals(emptyList<String>(), task.tags)
	}

	@Test
	fun titleStartingWithAKeywordCommentOrCookieGetsALeadingZeroWidthSpace() {
		for (t in listOf("TODO list review", "DONE", "COMMENT out", "[#A] first")) assertEquals("$z$t", Escape.title(t, keywords))
		assertEquals("TODOS", Escape.title("TODOS", keywords))
		val text = Fixtures.text("escape-keyword.expected.org")
		val written = edit("todo.org", Fixtures.IO_LIST, FieldChange.Priority(null), FieldChange.State(null), FieldChange.Title("TODO list review"))
		assertEquals(text, written)
		val task = Fixtures.entry(text, Fixtures.IO_LIST)
		assertEquals(null, task.state)
		assertEquals("${z}TODO list review", task.title)
	}

	// Text that holds the escape reads as no structure, so the form can send back what it read.
	@Test
	fun escapingTwiceAddsNaught() {
		for (t in listOf("TODO x", "a :b:", "[#A] x")) assertEquals(Escape.title(t, keywords), Escape.title(Escape.title(t, keywords), keywords))
		val body = "* x\n#+TODO: A\n:NOTES:\n:END:\nSCHEDULED: soon"
		val once = Escape.bodyLines(body, planningFirst = true)
		assertEquals(once, Escape.bodyLines(once.joinToString("\n"), planningFirst = true))
	}

	@Test
	fun bodyLinesThatOrgWouldReadAsStructureGetAZeroWidthSpace() {
		val body = "* Not a heading\n  #+TODO: A | B\n:NOTES:\nkept\n:END:\nDEADLINE: <2026-12-01 Tue>"
		assertEquals(
			listOf("$z* Not a heading", "$z  #+TODO: A | B", "$z:NOTES:", "kept", ":END:", "DEADLINE: <2026-12-01 Tue>"),
			Escape.bodyLines(body, planningFirst = true),
		)
		// A planning-like line escapes only first, and only where it would follow the heading line.
		assertEquals(listOf("${z}SCHEDULED: soon"), Escape.bodyLines("SCHEDULED: soon", planningFirst = true))
		assertEquals(listOf("SCHEDULED: soon"), Escape.bodyLines("SCHEDULED: soon", planningFirst = false))
		val plain = listOf(" * an indented list item", "*bold*", ":NOTES: w/o an end", "<2026-11-23 Mon> call the rep", ",* a comma", "#+begin_quote")
		assertEquals(plain, Escape.bodyLines(plain.joinToString("\n"), planningFirst = true))
		// Through the writer and the reader: the entry holds the escaped text, and the file its headings.
		val text = edit("todo.org", Fixtures.VFD, FieldChange.Body(body))
		assertEquals(Escape.bodyLines(body, planningFirst = false).joinToString("\n"), Fixtures.entry(text, Fixtures.VFD).body)
		assertEquals(OrgDocument.parse(Fixtures.text("todo.org")).headings.size, OrgDocument.parse(text).headings.size)
	}

	// The body of an event is the text after its stamp lines; a line w/ a timestamp stays as typed.
	@Test
	fun eventBodyFollowsItsStampLines() {
		val body = "Call the rep\n<2026-11-30 Mon> then this"
		val pm = Fixtures.entry(edit("calendar.org", Fixtures.PM, FieldChange.Body(body)), Fixtures.PM)
		assertEquals(body, pm.event?.body)
		assertEquals(listOf(Fixtures.ts("<2026-11-23 Mon 09:00-10:00>")), pm.event?.timestamps)
	}
}
