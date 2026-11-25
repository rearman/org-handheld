package com.rearman.orgpalm.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

// Every file reads under the user's settings, as make-fixtures.el reads it.
class RecordsTest {
	private fun entries(name: String) = Fixtures.file(name).entries

	private fun parse(text: String) = OrgFile.parse("", text, OrgSettings.STARTING)

	// The one heading of [entries] whose line begins w/ [prefix].
	private fun List<Entry>.at(prefix: String) = single { it.anchor.headingLine.startsWith(prefix) }

	// One heading whose body is the lines [stamps].
	private fun event(vararg stamps: String): Entry =
		parse("* E\n" + stamps.joinToString("") { "$it\n" }).entries.single()

	// The leading stamp lines stay in the body; the event view holds their stamps and the rest.
	@Test
	fun readsEvents() {
		val calendar = entries("calendar.org")
		val pm = calendar[0]
		val pmEvent = pm.event!!
		assertEquals("Compressor PM, Plant 2", pm.title)
		assertEquals(listOf(Fixtures.ts("<2026-11-23 Mon 09:00-10:00>")), pmEvent.timestamps)
		assertNull(pmEvent.repeater)
		assertEquals(15, pmEvent.warnMinutes)
		assertEquals("<2026-11-23 Mon 09:00-10:00>\nBring the oil analysis kit.", pm.body)
		assertEquals("Bring the oil analysis kit.", pmEvent.body)
		val office = calendar[1]
		assertNull(office.event!!.timestamps.single().start)
		assertNull(office.event!!.warnMinutes)
		assertEquals("", office.event!!.body)
	}

	@Test
	fun readsAndBuildsWeekdaySets() {
		val walkdown = entries("calendar.org")[2].event!!
		assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), walkdown.weekdays)
		assertEquals(Repeater("+", 2, RepeatUnit.WEEK), walkdown.repeater)
		assertEquals(walkdown.timestamps, OrgFile.weekdayTimestamps(walkdown.timestamps[0], walkdown.weekdays))
		val dotted = event("<2026-11-23 Mon 07:00 .+1w>", "<2026-11-25 Wed 07:00 .+1w>").event!!
		assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), dotted.weekdays)
	}

	@Test
	fun onlyAWeeklyRepeatMakesAWeekdaySet() {
		assertNull(event("<2026-11-23 Mon 07:00>", "<2026-11-26 Thu 07:00>").event)
		assertNull(event("<2026-11-23 Mon 07:00 +1d>", "<2026-11-26 Thu 07:00 +1d>").event)
		assertNull(event("<2026-11-23 Mon 07:00 +1m>", "<2026-11-26 Thu 07:00 +1m>").event)
		assertNull(event("<2026-11-23 Mon 07:00 +1w>", "<2026-11-26 Thu 07:00 +2w>").event)
		assertNull(event("<2026-11-23 Mon 07:00 +1w>", "<2026-12-03 Thu 07:00 +1w>").event)
		assertEquals(1, event("<2026-11-23 Mon 07:00 +1d>").event?.timestamps?.size)
	}

	// Other shapes give no event and lock naught; the lines stay body text (DESIGN.md section 3).
	@Test
	fun otherShapesGiveNoEvent() {
		assertEquals(listOf(null, null, null), entries("readonly.org").map { it.event })
		for (stamps in listOf(
			listOf("[2026-11-20 Fri]"), listOf("<2026-11-23 Mon .+1d/3d>"), listOf("<2026-11-23 Mon 09:00 +4h>"),
			listOf("<2026-11-23 Mon 07:00 +1w> <2026-11-26 Thu 07:00 +1w>"), listOf("<2026-11-23 Mon> call the rep"),
			listOf("<2026-11-23 Mon 07:00 +1w -1d>", "<2026-11-26 Thu 07:00 +1w>"),
		)) {
			val e = event(*stamps.toTypedArray())
			assertNull(stamps.toString(), e.event)
			assertEquals(stamps.joinToString("\n"), e.body)
		}
	}

	// A planning stamp that does not parse locks its own keyword alone.
	@Test
	fun unreadablePlanningStampLocksItsKeywordAlone() {
		val closed = parse("* DONE Old job\nCLOSED: [2026-13-40 Fri 10:00]\n").entries.single()
		assertEquals(listOf(true, false, false), listOf(closed.closedLocked, closed.scheduledLocked, closed.deadlineLocked))
		val text = "* TODO Third Wednesday check\nSCHEDULED: <%%(diary-float t 3 3)> DEADLINE: <2026-12-01 Tue>\n"
		val diary = parse(text).entries.single()
		assertEquals(listOf(false, true, false), listOf(diary.closedLocked, diary.scheduledLocked, diary.deadlineLocked))
		assertEquals(Fixtures.ts("<2026-12-01 Tue>"), diary.deadline)
	}

	@Test
	fun readsTasksWithTheFileKeywords() {
		val todo = Fixtures.file("todo.org")
		assertEquals(listOf("TODO", "NEXT", "WAITING"), todo.keywords.open)
		assertEquals(listOf("DONE", "CANCELED"), todo.keywords.done)
		val vfd = todo.entries.single { it.property("ID") == Fixtures.VFD }
		assertEquals("todo.org", vfd.file)
		assertEquals(listOf("Work"), vfd.outlinePath)
		assertEquals(2, vfd.level)
		assertEquals("Work", vfd.category)
		assertEquals("TODO", vfd.state)
		assertFalse(vfd.done)
		assertEquals("A", vfd.priority)
		assertEquals("Order VFD spares", vfd.title)
		assertEquals(LocalDate.of(2026, 11, 25), vfd.deadline?.date)
		assertEquals(LocalDate.of(2026, 11, 23), vfd.scheduled?.date)
		assertNull(vfd.closed)
		assertEquals("Two ACS580s, frame R3.", vfd.body)
		val io = todo.entries.single { it.property("ID") == Fixtures.IO_LIST }
		assertEquals("DONE", io.state)
		assertTrue(io.done)
		assertEquals("C", io.priority)
		assertEquals(Fixtures.ts("[2026-11-20 Fri 16:42]"), io.closed)
	}

	// Org merges the #+TYP_TODO: lines, then the #+TODO: lines, then the #+SEQ_TODO: lines; the first
	// #+PRIORITIES: line wins (org-set-regexps-and-options, org-collect-keywords).
	@Test
	fun settingLinesFollowOrg() {
		val text = "#+SEQ_TODO: OPEN | SHUT\n#+PRIORITIES: 1 10 5\n#+TODO: TODO WAIT(w@) | DONE\n#+PRIORITIES: A E C\n#+TYP_TODO: ANNA BOB\n"
		val f = parse(text)
		assertEquals(listOf("ANNA", "BOB", "TODO", "WAIT", "DONE", "OPEN", "SHUT"), f.keywords.all)
		assertEquals(listOf("BOB", "DONE", "SHUT"), f.keywords.done)
		assertEquals("TODO", f.keywords.sequenceOf("WAIT")?.head)
		assertTrue(f.keywords.sequenceOf("ANNA")!!.type)
		assertEquals(Priorities(1, 10, 5), f.priorities)
		assertEquals((1..10).map { it.toString() }, f.priorities.values)
		// org-priority-to-value per word: the first digits, else the first character.
		val mixed = parse("#+PRIORITIES: A1 C B\n").priorities
		assertEquals(Priorities(1, 'C'.code, 'B'.code), mixed)
		assertEquals(OrgSettings.STARTING.priorities, parse("#+PRIORITIES: 99999999999 C B\n").priorities)
	}

	@Test
	fun readsNotes() {
		val frick = entries("memos.org").single { it.property("ID") == Fixtures.FRICK }
		assertEquals("Frick rep contact", frick.title)
		assertEquals("Ask about the RWF II oil pump seal kit.", frick.body)
		assertNull(frick.state)
		assertEquals("Work", frick.category)
	}

	@Test
	fun foreignDrawersStayOutOfTheFields() {
		val vendor = entries("foreign.org").single()
		assertEquals(10, vendor.event!!.warnMinutes)
		assertEquals(listOf(Fixtures.ts("<2026-12-02 Wed 13:30-15:00>")), vendor.event!!.timestamps)
		assertEquals("Meet the Frick rep.", vendor.event!!.body)
		assertEquals("Plant 2 lobby", vendor.property("location"))
		val task = entries("logbook.org").single { it.level == 2 }
		assertEquals("Use the Fluke 754.", task.body)
	}

	// Each heading's category, tags w/ inheritance, and outline path, as inheritance.txt records what
	// Emacs gives: :CATEGORY: of the entry or an ancestor, else the last #+CATEGORY:, else the file name;
	// #+FILETAGS: and the ancestors' tags ahead of the own, less :undertaking: (excluded from
	// inheritance in user.org).
	@Test
	fun inheritanceFollowsOrg() {
		val rows = Fixtures.text("inheritance.txt").lines().filter { it.isNotEmpty() && !it.startsWith("#") }
		assertEquals(31, rows.size)
		val split = { s: String, sep: String -> if (s.isEmpty()) emptyList() else s.split(sep) }
		for (row in rows) {
			val (file, line, category, tags, path) = row.split('\t')
			val e = entries(file).single { it.anchor.lineIndex == line.toInt() }
			assertEquals(row, category, e.category)
			assertEquals(row, split(tags, ":"), e.allTags)
			assertEquals(row, split(path, "|"), e.outlinePath)
		}
	}

	// The settings' priority range serves a file w/o a #+PRIORITIES: line: [#1] reads as the highest.
	@Test
	fun numericCookieReadsUnderTheSettingsRange() {
		val file = parse("* TODO [#1] Call\n")
		assertEquals(Priorities(1, 5, 5), file.priorities)
		assertEquals(file.priorities.values.first(), file.entries.single().priority)
		assertEquals(Priorities.DEFAULT, parse("#+PRIORITIES: A C B\n").priorities)
	}

	// The keywords of org-todo-keywords serve a file w/o #+TODO: lines, w/ their log marks; a file's
	// own lines win, and its #+STARTUP: log words win over the log variables.
	@Test
	fun settingsYieldToTheFileAsOrgLetsThem() {
		val plain = parse("* NEXT Call\n")
		assertEquals(listOf("TODO", "NEXT", "WAITING", "DONE", "BELAYED"), plain.keywords.all)
		assertEquals(listOf("DONE", "BELAYED"), plain.keywords.done)
		assertEquals(KeywordLog(LogMode.NOTE, LogMode.TIME), plain.keywords.logOf("WAITING"))
		assertEquals(KeywordLog(LogMode.TIME, LogMode.NONE), plain.keywords.logOf("DONE"))
		assertNull(plain.keywords.logOf("NEXT"))
		assertEquals("NEXT", plain.entries.single().state)
		val own = parse("#+TODO: OPEN | SHUT\n#+STARTUP: nologdone logdrawer\n")
		assertEquals(listOf("OPEN", "SHUT"), own.keywords.all)
		assertEquals(LogMode.NONE, own.settings.logDone)
		assertEquals(OrgSettings.LOGBOOK, own.settings.logIntoDrawer)
	}

	@Test
	fun ownAndInheritedTagsAreKeptApart() {
		val outline = entries("outline.org")
		val filters = outline.at("*** NEXT Order filters")
		assertEquals(listOf("plant"), filters.tags)
		assertEquals(listOf("mech", "urgent"), filters.inheritedTags)
		val electrical = outline.at("**** Electrical")
		assertEquals(listOf("mech"), electrical.tags)
		assertEquals(listOf("plant"), electrical.inheritedTags)
	}

	// Stamp lines read at any level, and a range stays body text w/o an event.
	@Test
	fun stampLinesReadAtEveryLevel() {
		val outline = entries("outline.org")
		val drain = outline.at("*** Drain the separator")
		assertEquals(listOf(Fixtures.ts("<2026-11-25 Wed 08:00-09:00>")), drain.event!!.timestamps)
		assertEquals("Two buckets.", drain.event!!.body)
		val vendor = outline.at("** Vendor meeting")
		assertNull(vendor.event)
		assertEquals("<2026-11-30 Mon 13:00>--<2026-11-30 Mon 15:00>\nRanged stamp.", vendor.body)
	}

	// The agenda shows an entry through its planning stamps and the dates of its own text, not its
	// children's; the todo list shows every open TODO entry, dated or not.
	@Test
	fun agendaInclusion() {
		val outline = entries("outline.org")
		val from = LocalDate.of(2026, 11, 23)
		val to = LocalDate.of(2026, 12, 31)
		val dates = { prefix: String -> outline.at(prefix).agendaDates(from, to).toList() }
		assertEquals(listOf(LocalDate.of(2026, 12, 4)), dates("*** TODO Submittal review"))
		assertEquals(listOf(LocalDate.of(2026, 11, 24)), dates("** TODO Oil change on C3"))
		assertEquals(listOf(LocalDate.of(2026, 11, 30)), dates("** Vendor meeting"))
		assertEquals(emptyList<LocalDate>(), dates("* Compressors"))
		assertEquals(
			listOf("Oil change on C3", "Order filters", "Submittal review", "Timesheets"),
			outline.filter { it.inTodoList }.map { it.title },
		)
	}

	// Ranges show on every day and the diary sexps the app models on their dates, as agenda.txt records
	// Org's agenda over 900 days; and a stamp counts in a drawer, the property drawer too, but not in a
	// comment line, a src block, or a fixed-width line. The rows hold their line breaks as "\n".
	@Test
	fun agendaDatesMatchOrg() {
		val rows = Fixtures.text("agenda.txt").lines().drop(1).filter { it.isNotEmpty() }
		assertEquals(22, rows.size)
		val from = LocalDate.of(2026, 11, 1)
		for (row in rows) {
			val (text, dates) = row.split('\t')
			val e = parse("* E\n" + text.replace("\\n", "\n") + "\n").entries.single()
			assertEquals(row, dates, e.agendaDates(from, from.plusDays(899)).joinToString(" "))
		}
	}

	// A missed SCHEDULED or DEADLINE item shows on today until it is done, keyword or none, once its
	// delay has run, & a deadline shows on today from its warning days ahead, the setting or its own
	// cookie, as due.txt records Org's agenda for the week from the fixed today.
	@Test
	fun dueItemsShowOnTodayAsInOrg() {
		val rows = Fixtures.text("due.txt").lines().drop(1).filter { it.isNotEmpty() }
		assertEquals(20, rows.size)
		val today = LocalDate.of(2026, 11, 23)
		for (row in rows) {
			val (text, dates) = row.split('\t')
			val e = parse(text.replace("\\n", "\n") + "\n").entries.single()
			assertEquals(row, dates, e.agendaDates(today, today.plusDays(6), today, OrgSettings.STARTING.deadlineWarningDays).joinToString(" "))
		}
	}

	// A sexp the app does not model shows on no date, as a bad sexp in Org; a COMMENT or :ARCHIVE:
	// subtree shows nowhere in the agenda.
	@Test
	fun unknownSexpsAndSkippedTrees() {
		val unknown = parse("* E\n<%%(org-class 2026 11 1 2026 12 1 2)>\n").entries.single()
		assertTrue(unknown.agendaDates(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)).isEmpty())
		val text = "* COMMENT Old\n** TODO Gone\n<2026-11-24 Tue>\n* Shelf :ARCHIVE:\n** TODO Kept\n* TODO Live\n<2026-11-24 Tue>\n"
		val entries = parse(text).entries
		assertEquals(listOf("Live"), entries.filter { it.inTodoList }.map { it.title })
		assertEquals(listOf(false, false, false, false, true), entries.map { it.inAgenda })
		assertTrue(entries[1].agendaDates(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)).isEmpty())
	}
}
