package com.rearman.orgpalm.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecordsTest {
	private fun file(kind: FileKind, name: String) = OrgFile.parse(kind, Fixtures.text(name))

	// One event whose timestamp lines are [stamps].
	private fun event(vararg stamps: String) =
		OrgFile.parse(FileKind.CALENDAR, "* E\n:PROPERTIES:\n:ID:       x\n:END:\n" + stamps.joinToString("") { "$it\n" }).events.single()

	@Test
	fun readsEvents() {
		val events = file(FileKind.CALENDAR, "calendar.org").events
		assertEquals(3, events.size)
		val pm = events[0]
		assertEquals(Fixtures.PM, pm.id)
		assertEquals("Compressor PM, Plant 2", pm.title)
		assertEquals(listOf(Fixtures.ts("<2026-11-23 Mon 09:00-10:00>")), pm.timestamps)
		assertNull(pm.repeater)
		assertEquals(15, pm.warnMinutes)
		assertEquals("Bring the oil analysis kit.", pm.body)
		assertFalse(pm.isPrivate)
		assertFalse(pm.readOnly)
		val office = events[1]
		assertTrue(office.isPrivate)
		assertNull(office.timestamps.single().start)
		assertNull(office.warnMinutes)
		assertEquals("", office.body)
	}

	@Test
	fun readsAndBuildsWeekdaySets() {
		val walkdown = file(FileKind.CALENDAR, "calendar.org").events[2]
		assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), walkdown.weekdays)
		assertEquals(Repeater("+", 2, RepeatUnit.WEEK), walkdown.repeater)
		assertFalse(walkdown.readOnly)
		assertEquals(walkdown.timestamps, OrgFile.weekdayTimestamps(walkdown.timestamps[0], walkdown.weekdays))
		val dotted = event("<2026-11-23 Mon 07:00 .+1w>", "<2026-11-25 Wed 07:00 .+1w>")
		assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), dotted.weekdays)
	}

	@Test
	fun onlyAWeeklyRepeatMakesAWeekdaySet() {
		assertTrue(event("<2026-11-23 Mon 07:00>", "<2026-11-26 Thu 07:00>").readOnly)
		assertTrue(event("<2026-11-23 Mon 07:00 +1d>", "<2026-11-26 Thu 07:00 +1d>").readOnly)
		assertTrue(event("<2026-11-23 Mon 07:00 +1m>", "<2026-11-26 Thu 07:00 +1m>").readOnly)
		assertFalse(event("<2026-11-23 Mon 07:00 +1d>").readOnly)
	}

	@Test
	fun marksUnreadableTimestampShapesReadOnly() {
		val events = file(FileKind.CALENDAR, "readonly.org").events
		assertEquals(listOf(true, true, true), events.map { it.readOnly })
		// Timestamps that differ in repeater, or that fall in different weeks, or two on one line.
		assertTrue(event("<2026-11-23 Mon 07:00 +1w>", "<2026-11-26 Thu 07:00 +2w>").readOnly)
		assertTrue(event("<2026-11-23 Mon 07:00 +1w>", "<2026-12-03 Thu 07:00 +1w>").readOnly)
		assertTrue(event("<2026-11-23 Mon 07:00 +1w> <2026-11-26 Thu 07:00 +1w>").readOnly)
		assertFalse(file(FileKind.CALENDAR, "calendar.org").events.any { it.readOnly })
	}

	@Test
	fun eventShapesTheAppDoesNotModelAreReadOnly() {
		assertTrue(event("[2026-11-20 Fri]").readOnly)
		assertTrue(event("[2026-11-23 Mon 07:00 +1w]", "[2026-11-26 Thu 07:00 +1w]").readOnly)
		assertTrue(event("<2026-11-23 Mon .+1d/3d>").readOnly)
		assertTrue(event("<2026-11-23 Mon 09:00 +4h>").readOnly)
		assertTrue(event("<2026-11-23 Mon 07:00 +1w -1d>", "<2026-11-26 Thu 07:00 +1w>").readOnly)
		val titled = OrgFile.parse(FileKind.CALENDAR, "* Call <2026-11-23 Mon>\n:PROPERTIES:\n:ID:       x\n:END:\n").events.single()
		assertTrue(titled.readOnly)
	}

	// A heading of level 2 ahead of the first level-1 heading is no category, and its children no tasks.
	@Test
	fun onlyLevelOneHeadingsAreCategories() {
		val f = OrgFile.parse(FileKind.TODO, "** Stray\n*** TODO Lost\n* Work\n:PROPERTIES:\n:ID:       w\n:END:\n** TODO Found\n")
		assertEquals(listOf(Category("w", "Work")), f.categories)
		assertEquals(listOf("Found"), f.tasks.map { it.title })
		assertNull(f.category("Stray"))
		// A level-3 heading right under a category is no task either.
		val deep = OrgFile.parse(FileKind.TODO, "* Work\n*** Deep\n** TODO Found\n")
		assertEquals(listOf("Found"), deep.tasks.map { it.title })
		assertFalse(deep.isRecord(deep.document.headings[1]))
	}

	@Test
	fun taskWithAnUnreadableClosedStampIsReadOnly() {
		val text = "* Work\n** DONE Old job\nCLOSED: [2026-13-40 Fri 10:00]\n"
		assertTrue(OrgFile.parse(FileKind.TODO, text).tasks.single().readOnly)
	}

	@Test
	fun readsTasksWithTheFileKeywords() {
		val todo = file(FileKind.TODO, "todo.org")
		assertEquals(listOf("TODO", "NEXT", "WAITING"), todo.keywords.open)
		assertEquals(listOf("DONE", "CANCELED"), todo.keywords.done)
		assertEquals(listOf(Category(Fixtures.WORK, "Work"), Category(Fixtures.UNFILED, "Unfiled")), todo.categories)
		val (vfd, io) = todo.tasks
		assertEquals(Fixtures.VFD, vfd.id)
		assertEquals(Fixtures.WORK, vfd.categoryId)
		assertEquals("Work", vfd.category)
		assertEquals("TODO", vfd.state)
		assertEquals("A", vfd.priority)
		assertEquals("Order VFD spares", vfd.title)
		assertEquals(LocalDate.of(2026, 11, 25), vfd.deadline?.date)
		assertEquals(LocalDate.of(2026, 11, 23), vfd.scheduled?.date)
		assertNull(vfd.closed)
		assertTrue(vfd.isPrivate)
		assertEquals("Two ACS580s, frame R3.", vfd.body)
		assertFalse(vfd.readOnly)
		assertEquals("DONE", io.state)
		assertTrue(todo.keywords.isDone(io.state))
		assertEquals("C", io.priority)
		assertEquals(Fixtures.ts("[2026-11-20 Fri 16:42]"), io.closed)
	}

	@Test
	fun taskWithAnUnreadablePlanningStampIsReadOnly() {
		val text = "* Work\n** TODO Third Wednesday check\nSCHEDULED: <%%(diary-float t 3 3)>\n"
		assertTrue(OrgFile.parse(FileKind.TODO, text).tasks.single().readOnly)
	}

	// Org merges the #+TYP_TODO: lines, then the #+TODO: lines, then the #+SEQ_TODO: lines; the first
	// #+PRIORITIES: line wins (org-set-regexps-and-options, org-collect-keywords).
	@Test
	fun settingLinesFollowOrg() {
		val memos = file(FileKind.MEMOS, "memos.org")
		assertEquals(TodoKeywords.DEFAULT, memos.keywords)
		assertEquals(listOf("TODO"), memos.keywords.open)
		assertEquals(Priorities.DEFAULT, memos.priorities)
		assertEquals(listOf("A", "B", "C"), memos.priorities.values)
		val text = "#+SEQ_TODO: OPEN | SHUT\n#+PRIORITIES: 1 10 5\n#+TODO: TODO WAIT(w@) | DONE\n#+PRIORITIES: A E C\n#+TYP_TODO: ANNA BOB\n"
		val f = OrgFile.parse(FileKind.TODO, text)
		assertEquals(listOf("ANNA", "BOB", "TODO", "WAIT", "DONE", "OPEN", "SHUT"), f.keywords.all)
		assertEquals(listOf("BOB", "DONE", "SHUT"), f.keywords.done)
		assertEquals("TODO", f.keywords.sequenceOf("WAIT")?.head)
		assertTrue(f.keywords.sequenceOf("ANNA")!!.type)
		assertEquals(Priorities(1, 10, 5), f.priorities)
		assertEquals((1..10).map { it.toString() }, f.priorities.values)
		// org-priority-to-value per word: the first digits, else the first character.
		val mixed = OrgFile.parse(FileKind.TODO, "#+PRIORITIES: A1 C B\n").priorities
		assertEquals(Priorities(1, 'C'.code, 'B'.code), mixed)
		assertEquals(Priorities.DEFAULT, OrgFile.parse(FileKind.TODO, "#+PRIORITIES: 99999999999 C B\n").priorities)
	}

	@Test
	fun readsNotes() {
		val notes = file(FileKind.MEMOS, "memos.org").notes
		assertEquals(listOf(Note(Fixtures.FRICK, Fixtures.MEMO_WORK, "Work", "Frick rep contact", "Ask about the RWF II oil pump seal kit.")), notes)
	}

	@Test
	fun categoryWithoutAnIdReads() {
		val categories = file(FileKind.TODO, "noid.org").categories
		assertEquals(listOf(Category("8e3b0d6f-5a19-4c72-9f4e-2b6d1a0c8e37", "Work"), Category(null, "Shop")), categories)
	}

	@Test
	fun foreignDrawersStayOutOfTheFields() {
		val vendor = file(FileKind.CALENDAR, "foreign.org").events.single()
		assertEquals(10, vendor.warnMinutes)
		assertEquals(listOf(Fixtures.ts("<2026-12-02 Wed 13:30-15:00>")), vendor.timestamps)
		assertEquals("Meet the Frick rep.", vendor.body)
		val task = file(FileKind.TODO, "logbook.org").tasks.single()
		assertEquals("Use the Fluke 754.", task.body)
	}
}
