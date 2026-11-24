package com.rearman.orgpalm.org

import com.rearman.orgpalm.org.Fixtures.ts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY

// Each test applies one edit and compares the text, byte for byte, w/ what Org's own command wrote for
// the same edit (tools/make-fixtures.el), under the user's settings; a test of another value copies them
// w/ that one value changed, as the case let-binds it in Emacs.
class OrgWriterTest {
	private val writer = Fixtures.writer()

	private fun written(result: WriteResult): String? = (result as? WriteResult.Written)?.text

	private fun check(case: String, input: String, with: OrgWriter = writer, edit: (String) -> Edit) {
		val text = Fixtures.text(input)
		assertEquals(Fixtures.text("$case.expected.org"), written(with.apply(text, edit(text))))
	}

	// An update of the heading whose :ID: is [id].
	private fun update(case: String, input: String, id: String, vararg changes: FieldChange) =
		check(case, input) { Edit.Update(Fixtures.anchor(it, id), changes.toList()) }

	// An update of the heading of user.org whose line begins w/ [prefix].
	private fun user(case: String, prefix: String, vararg changes: FieldChange) =
		check(case, "user.org") { Edit.Update(Fixtures.headingAnchor(it, prefix), changes.toList()) }

	// States.

	// DONE(d!): CLOSED per org-log-done, and a state line after the drawers.
	@Test
	fun doneLogsClosedAndAStateLine() = user("user-done", "** NEXT Order the fan motor", FieldChange.State("DONE"))

	@Test
	fun stateFromDoneRemovesClosed() = update("todo-reopen", "todo.org", Fixtures.IO_LIST, FieldChange.State("TODO"))

	// WAITING(w@/!): the note the form took, below the state line.
	@Test
	fun waitingLogsItsNote() = user("user-waiting-note", "** TODO Pick up the motor", FieldChange.State("WAITING", "Waiting on the vendor quote."))

	@Test
	fun emptyNoteLogsTheLineAlone() = user("user-empty-note", "** TODO Pick up the motor", FieldChange.State("WAITING", ""))

	// WAITING(w@/!) logs on leaving it, above the older note.
	@Test
	fun leavingWaitingLogs() = user("user-leave-waiting", "** WAITING Electrician quote", FieldChange.State("TODO"))

	// org-log-into-drawer: the drawer is made after the planning line and takes the note.
	@Test
	fun logGoesIntoADrawer() = check("user-drawer", "user.org", Fixtures.writer(OrgSettings.STARTING.copy(logIntoDrawer = OrgSettings.LOGBOOK))) {
		Edit.Update(Fixtures.headingAnchor(it, "** TODO Pick up the motor"), listOf(FieldChange.State("DONE")))
	}

	// #+STARTUP: logdrawer in the file wins over org-log-into-drawer, ahead of the body text.
	@Test
	fun startupLogdrawerWins() = check("startup-drawer", "startup.org") {
		Edit.Update(Fixtures.headingAnchor(it, "* TODO Fix the door"), listOf(FieldChange.State("DONE")))
	}

	// An open child blocks DONE, and Emacs leaves the file as it was.
	@Test
	fun openChildBlocksDone() {
		val text = Fixtures.text("user.org")
		val edit = Edit.Update(Fixtures.headingAnchor(text, "* Shop renovation"), listOf(FieldChange.State("DONE")))
		assertEquals(WriteResult.Blocked("\"NEXT Order the fan motor :call:\""), writer.apply(text, edit))
		assertEquals(text, Fixtures.text("user-blocked.expected.org"))
	}

	@Test
	fun uncheckedBoxBlocksDone() {
		val text = Fixtures.text("user.org")
		val edit = Edit.Update(Fixtures.headingAnchor(text, "** TODO Hardware store run"), listOf(FieldChange.State("DONE")))
		assertEquals(WriteResult.Blocked("contained checkboxes"), writer.apply(text, edit))
		assertEquals(text, Fixtures.text("user-blocked-box.expected.org"))
	}

	@Test
	fun crlfFileKeepsItsLineEndings() = update("crlf-done", "crlf.org", Fixtures.GASKET, FieldChange.State("DONE"))

	// Planning.

	@Test
	fun rescheduleLogsTheOldDate() = user("user-reschedule", "** NEXT Order the fan motor", FieldChange.Scheduled(ts("<2026-11-30 Mon>")))

	@Test
	fun newDeadlineLogsTheOldDate() = update("deadline-set", "todo.org", Fixtures.VFD, FieldChange.Deadline(ts("<2026-11-27 Fri>")))

	@Test
	fun scheduleAddedDropsClosedAsOrgDoes() = update("schedule-add", "todo.org", Fixtures.IO_LIST, FieldChange.Scheduled(ts("<2026-11-30 Mon>")))

	@Test
	fun scheduleAddedBesideADeadline() =
		update("schedule-beside-deadline", "children.org", Fixtures.REBUILD, FieldChange.Scheduled(ts("<2026-12-02 Wed>")))

	@Test
	fun unscheduleLogsTheOldDate() = user("user-unschedule", "** NEXT Order the fan motor", FieldChange.Scheduled(null))

	// Org removes every "DEADLINE: <...>" of the entry, the body's too (DESIGN.md meant to keep it).
	@Test
	fun deadlineRemovalLogsAndTakesTheBodyStampToo() = user("user-undeadline", "** TODO Call the vendor", FieldChange.Deadline(null))

	// A date and a state in one save log as two commands do: the state's note above the date's.
	@Test
	fun stateAndDateInOneSave() =
		user("user-state-and-date", "** NEXT Order the fan motor", FieldChange.State("DONE"), FieldChange.Scheduled(ts("<2026-11-30 Mon>")))

	// An unreadable planning stamp locks its own keyword alone.
	@Test
	fun lockedPlanningStampRefusesItsKeywordAlone() {
		val text = "* TODO Third Wednesday check\nSCHEDULED: <%%(diary-float t 3 3)>\n"
		val anchor = OrgFile.parse("", text).entries.single().anchor
		assertEquals(WriteResult.Locked, writer.apply(text, Edit.Update(anchor, listOf(FieldChange.Scheduled(ts("<2026-12-02 Wed>"))))))
		val due = written(writer.apply(text, Edit.Update(anchor, listOf(FieldChange.Deadline(ts("<2026-12-04 Fri>"))))))
		assertEquals("* TODO Third Wednesday check\nDEADLINE: <2026-12-04 Fri> SCHEDULED: <%%(diary-float t 3 3)>\n", due)
	}

	// The heading line.

	// org-tags-column 0: the tags realign one space after the new title.
	@Test
	fun titleChangeRealignsTags() = update("title-edit", "todo.org", Fixtures.VFD, FieldChange.Title("Order VFD spares & fuses"))

	// A numeric range from the settings: a cookie of 1, and of the default 5, which Org writes too.
	@Test
	fun numericPriorityWritten() = user("user-priority-set", "** TODO Pick up the motor", FieldChange.Priority("1"))

	@Test
	fun defaultPriorityWritesItsCookie() = user("user-priority-default", "** TODO Pick up the motor", FieldChange.Priority("5"))

	@Test
	fun priorityRemoval() = update("priority-remove", "todo.org", Fixtures.VFD, FieldChange.Priority(null))

	@Test(expected = IllegalArgumentException::class)
	fun priorityOutsideTheRangeIsRefused() {
		val text = Fixtures.text("todo.org")
		writer.apply(text, Edit.Update(Fixtures.anchor(text, Fixtures.VFD), listOf(FieldChange.Priority("D"))))
	}

	// org-tags-column -77, Org's own value: the tags end at column 77.
	@Test
	fun tagsAddedAlignToColumn77() = check("tags-set", "todo.org", Fixtures.writer(OrgSettings.STARTING.copy(tagsColumn = -77))) {
		Edit.Update(Fixtures.anchor(it, Fixtures.IO_LIST), listOf(FieldChange.Tags(listOf("work", "plc"))))
	}

	@Test
	fun tagsCleared() = update("tags-clear", "todo.org", Fixtures.VFD, FieldChange.Tags(emptyList()))

	@Test
	fun unchangedValuesLeaveTheTextAlone() {
		val text = Fixtures.text("todo.org")
		val same = listOf(
			FieldChange.Title("Order VFD spares"), FieldChange.State("TODO"), FieldChange.Priority("A"),
			FieldChange.Tags(listOf("private")), FieldChange.Deadline(ts("<2026-11-25 Wed>")),
			FieldChange.Scheduled(ts("<2026-11-23 Mon>")), FieldChange.Body("Two ACS580s, frame R3."),
		)
		assertEquals(text, written(writer.apply(text, Edit.Update(Fixtures.anchor(text, Fixtures.VFD), same))))
	}

	// Properties, the body, and event timestamps.

	@Test
	fun warnTimeAdded() = update("warn-set", "calendar.org", Fixtures.OFFICE, FieldChange.warnMinutes(30))

	@Test
	fun warnTimeRemoved() = update("warn-remove", "calendar.org", Fixtures.PM, FieldChange.warnMinutes(null))

	@Test
	fun foreignPropertiesStayInPlace() = update("foreign-warn", "foreign.org", Fixtures.VENDOR, FieldChange.warnMinutes(20))

	@Test
	fun lastPropertyDeletedTakesTheDrawer() = update("drawer-remove", "children.org", Fixtures.ORDER_GASKETS, FieldChange.Property("ID", null))

	@Test
	fun bodyChangedAfterTheDrawers() =
		update("logbook-body", "logbook.org", Fixtures.TRANSMITTER, FieldChange.Body("Use the Fluke 754.\nZero at 0 psig."))

	@Test
	fun weekdaySetWritten() = update(
		"weekdays-add", "calendar.org", Fixtures.PM,
		FieldChange.Timestamps(OrgFile.weekdayTimestamps(ts("<2026-11-23 Mon 09:00-10:00 +1w>"), setOf(MONDAY, THURSDAY))),
	)

	@Test
	fun weekdaySetNarrowed() = update(
		"weekdays-remove", "calendar.org", Fixtures.WALKDOWN,
		FieldChange.Timestamps(OrgFile.weekdayTimestamps(ts("<2026-11-23 Mon 07:00-08:00 +2w>"), setOf(MONDAY))),
	)

	@Test
	fun repeaterChangedOnEveryTimestamp() = update(
		"repeater-change", "calendar.org", Fixtures.WALKDOWN,
		FieldChange.Timestamps(OrgFile.weekdayTimestamps(ts("<2026-11-23 Mon 07:00-08:00 +1w>"), setOf(MONDAY, THURSDAY))),
	)

	// An event's body goes after its stamp lines, which stay as they were.
	@Test
	fun eventBodyFollowsItsStampLines() {
		val text = Fixtures.text("calendar.org")
		val body = "Call the rep\n<2026-11-30 Mon> then this"
		val out = written(writer.apply(text, Edit.Update(Fixtures.anchor(text, Fixtures.PM), listOf(FieldChange.Body(body)))))!!
		val pm = Fixtures.entry(out, Fixtures.PM)
		assertEquals(body, pm.event?.body)
		assertEquals(listOf(Fixtures.ts("<2026-11-23 Mon 09:00-10:00>")), pm.event?.timestamps)
	}

	@Test
	fun unchangedEventValuesLeaveTheTextAlone() {
		val text = Fixtures.text("calendar.org")
		val walkdown = Fixtures.entry(text, Fixtures.WALKDOWN)
		val same = listOf(
			FieldChange.Title(walkdown.title), FieldChange.Tags(walkdown.tags), FieldChange.warnMinutes(null),
			FieldChange.Timestamps(walkdown.event!!.timestamps), FieldChange.Body(walkdown.event!!.body),
		)
		assertEquals(text, written(writer.apply(text, Edit.Update(walkdown.anchor, same))))
	}

	// A stamp whose value stays keeps its text, though Org would write it otherwise.
	@Test
	fun unchangedTimestampLinesStayByteForByte() {
		val text = "* E\n:PROPERTIES:\n:ID:       x\n:END:\n<2026-11-23 Monday 7:00 +1w>\n<2026-11-26 Thu 07:00 +1w>\n"
		val stamps = listOf(ts("<2026-11-23 Mon 07:00 +1w>"), ts("<2026-11-27 Fri 07:00 +1w>"))
		val out = writer.apply(text, Edit.Update(Fixtures.anchor(text, "x"), listOf(FieldChange.Timestamps(stamps))))
		assertEquals(text.replace("<2026-11-26 Thu 07:00 +1w>", "<2026-11-27 Fri 07:00 +1w>"), written(out))
	}

	// A range is body text, no stamp line: it locks naught, and new stamp lines go ahead of it.
	@Test
	fun rangeLineLocksNothing() {
		val text = Fixtures.text("readonly.org")
		val shutdown = Fixtures.anchor(text, Fixtures.SHUTDOWN)
		val stamps = FieldChange.Timestamps(listOf(ts("<2026-12-21 Mon>")))
		val out = written(writer.apply(text, Edit.Update(shutdown, listOf(stamps))))
		assertEquals(text.replace("<2026-12-21 Mon>--", "<2026-12-21 Mon>\n<2026-12-21 Mon>--"), out)
	}

	// Repeating tasks marked done: org-auto-repeat-maybe.

	private fun repeat(case: String, id: String) = update(case, "repeat.org", id, FieldChange.State("DONE"))

	// DONE(d!) on a repeating task: the state line once, :LAST_REPEAT:, and the date moved on.
	@Test
	fun repeatingTaskLogsItsStateLineOnce() = user("user-repeat", "** NEXT Weekly oil check", FieldChange.State("DONE"))

	@Test
	fun repeatPlusMovesOneInterval() = repeat("repeat-plus", Fixtures.OIL_CHECK)

	@Test
	fun repeatDotMovesFromTodayAndReturnsToTheHead() = repeat("repeat-dot", Fixtures.FILTER)

	@Test
	fun repeatPlusPlusMovesPastToday() = repeat("repeat-plusplus", Fixtures.REPORT)

	@Test
	fun repeatMonthlyFromThe31st() = repeat("repeat-month-end", Fixtures.VALVE_TEST)

	@Test
	fun repeatDropsAScheduledStampWithoutARepeater() = repeat("repeat-plain-scheduled", Fixtures.LOG_REVIEW)

	@Test
	fun repeatAtTheEndOfTheFileWithAWarning() = repeat("repeat-warning", Fixtures.SENSOR)

	// The last step of a repeat drops a "--2d" delay, as org-timestamp-change w/ suppress-tmp-delay does.
	@Test
	fun repeatDropsADelay() = repeat("repeat-delay", Fixtures.DELAYED)

	// A :REPEAT_TO_STATE: that names no keyword of the file counts for naught.
	@Test
	fun repeatIgnoresAnUnknownRepeatToState() = repeat("repeat-to-unknown-state", Fixtures.GAUGE)

	// The first repeating stamp has a count of zero, so Org repeats naught and writes CLOSED.
	@Test
	fun zeroCountFirstStampRepeatsNaught() = repeat("repeat-zero-first", Fixtures.ZERO_FIRST)

	// A repeating stamp in the body moves on w/ the planning line.
	@Test
	fun repeatMovesABodyStamp() = repeat("repeat-body-stamp", Fixtures.SITE_VISIT)

	// The log note skips the blank line after the drawer and goes ahead of the next heading.
	@Test
	fun repeatWithoutAKeywordLogsAnEmptyOldState() = update("repeat-no-keyword", "repeat2.org", Fixtures.SWEEP, FieldChange.State("DONE"))

	// The note skips the blank line and goes ahead of the body text.
	@Test
	fun repeatHabitKeepsItsSuffix() = update("repeat-habit", "repeat2.org", Fixtures.STRETCH, FieldChange.State("DONE"))

	@Test
	fun repeatGoesToRepeatToState() = update("repeat-to-state", "repeat2.org", Fixtures.ROUNDS, FieldChange.State("DONE"))

	// A #+TYP_TODO: state stays; at the end of the file the note follows the blank line w/o a newline.
	@Test
	fun repeatKeepsATypedState() = update("repeat-typed", "repeat2.org", Fixtures.ANNA, FieldChange.State("FINISHED"))

	// org-log-done 'note on a repeating task w/o marks: the CLOSING NOTE alone, since
	// org-auto-repeat-maybe finds a note set up already.
	@Test
	fun closingNoteOnARepeatLogsOnce() = check("repeat-closing-note", "repeat.org", Fixtures.writer(OrgSettings.STARTING.copy(logDone = LogMode.NOTE))) {
		Edit.Update(Fixtures.anchor(it, Fixtures.OIL_CHECK), listOf(FieldChange.State("DONE", "Checked.")))
	}

	// One task w/ the scheduled stamp [stamp], marked done at Fixtures.NOW (14:30).
	private fun hourly(stamp: String): String =
		"* Work\n** TODO Check\nSCHEDULED: $stamp\n:PROPERTIES:\n:ID:       h\n:END:\n"

	private fun markDone(text: String): String =
		written(writer.apply(text, Edit.Update(Fixtures.anchor(text, "h"), listOf(FieldChange.State("DONE"))))) ?: error("Not written")

	@Test
	fun hourlyRepeatsMoveFromNow() {
		assertTrue(markDone(hourly("<2026-11-23 Mon 09:00 .+4h>")).contains("SCHEDULED: <2026-11-23 Mon 18:30 .+4h>"))
		assertTrue(markDone(hourly("<2026-11-23 Mon 09:00 ++4h>")).contains("SCHEDULED: <2026-11-23 Mon 17:00 ++4h>"))
		assertTrue(markDone(hourly("<2026-11-23 Mon 09:00 +4h>")).contains("SCHEDULED: <2026-11-23 Mon 13:00 +4h>"))
	}

	@Test(expected = IllegalArgumentException::class)
	fun hourlyRepeatWithoutATimeIsRefused() {
		markDone(hourly("<2026-11-23 Mon +4h>"))
	}

	@Test
	fun plusPlusZeroStaysPut() {
		val text = "* Work\n** TODO Check\nDEADLINE: <2026-11-20 Fri +1w> SCHEDULED: <2026-11-18 Wed ++0d>\n:PROPERTIES:\n:ID:       h\n:END:\n"
		assertTrue(markDone(text).contains("DEADLINE: <2026-11-27 Fri +1w> SCHEDULED: <2026-11-18 Wed ++0d>"))
	}

	@Test
	fun dotRepeatOfZeroMovesToTodayBesideALiveRepeater() {
		val text = "* Work\n** TODO Check\nDEADLINE: <2026-11-20 Fri +1w> SCHEDULED: <2026-11-18 Wed .+0d>\n:PROPERTIES:\n:ID:       h\n:END:\n"
		assertTrue(markDone(text).contains("DEADLINE: <2026-11-27 Fri +1w> SCHEDULED: <2026-11-23 Mon .+0d>"))
	}

	// New entries: first per the user's capture position (org-capture w/ :prepend t).

	private val vendor = listOf(FieldChange.Title("Call the vendor"), FieldChange.State("TODO"))

	@Test
	fun capturedFirstUnderAHeading() {
		val text = Fixtures.text("outline.org")
		val result = writer.apply(text, Edit.Insert(Fixtures.headingAnchor(text, "* Projects"), vendor))
		assertEquals(WriteResult.Written(Fixtures.text("capture-first.expected.org"), 24), result)
	}

	// At the top level the blank line before the first heading goes, as org-capture drops it.
	@Test
	fun capturedFirstAtTheTopLevel() {
		val text = Fixtures.text("outline.org")
		assertEquals(WriteResult.Written(Fixtures.text("capture-first-top.expected.org"), 6), writer.apply(text, Edit.Insert(null, vendor)))
	}

	// The capture position last: org-insert-heading at the end of the parent's subtree.
	private val last = Fixtures.writer(OrgSettings.STARTING.copy(capturePosition = Position.LAST))

	@Test
	fun insertedLastAtTheEndOfItsParent() = check("task-append", "todo.org", last) {
		Edit.Insert(
			Fixtures.anchor(it, Fixtures.UNFILED),
			listOf(FieldChange.Title("Order spare contactors"), FieldChange.State("TODO"), FieldChange.Deadline(ts("<2026-12-01 Tue>"))),
		)
	}

	// A blank line goes after the new heading too, ahead of the next heading.
	@Test
	fun insertedLastAheadOfTheNextHeading() = check("task-append-middle", "todo.org", last) {
		Edit.Insert(Fixtures.anchor(it, Fixtures.WORK), listOf(FieldChange.Title("Check relief valves")))
	}

	@Test
	fun eventInsertedAtTheEndOfTheFile() = check("event-append", "calendar.org", last) {
		Edit.Insert(
			null,
			listOf(FieldChange.Title("Ammonia leak drill"), FieldChange.warnMinutes(30), FieldChange.Timestamps(listOf(ts("<2026-12-03 Thu 13:30-14:30>")))),
		)
	}

	// Org ends the new heading line w/ a newline, though the file had none.
	@Test
	fun insertedIntoAFileWithoutAFinalNewline() = check("memo-append", "no-final-newline.org", last) {
		Edit.Insert(Fixtures.anchor(it, Fixtures.NF_UNFILED), listOf(FieldChange.Title("Gate codes")))
	}

	@Test
	fun bomFileInsertedInto() = check("bom-append", "bom.org", last) { Edit.Insert(null, listOf(FieldChange.Title("Second"))) }

	@Test
	fun mixedFileInsertedInto() = check("mixed-append", "mixed.org", last) { Edit.Insert(null, listOf(FieldChange.Title("B"))) }

	@Test
	fun emptyFileInsertedInto() = check("empty-append", "empty.org", last) {
		Edit.Insert(null, listOf(FieldChange.Title("First event"), FieldChange.Timestamps(listOf(ts("<2026-12-01 Tue>")))))
	}

	// Delete and refile.

	@Test
	fun childHeadingsGoWithADeletedEntry() = check("children-delete", "children.org") { Edit.Delete(Fixtures.anchor(it, Fixtures.REBUILD)) }

	// The refile position first: right after the target's own section.
	@Test
	fun refiledFirstUnderItsTarget() = check("user-refile", "user.org") {
		Edit.Refile(Fixtures.headingAnchor(it, "** TODO Hardware store run"), Fixtures.headingAnchor(it, "* Shop renovation"))
	}

	// The refile position first w/o a target: atop the file's first heading, at level 1.
	@Test
	fun refiledFirstAtTheTopLevel() = check("user-refile-top", "user.org") { Edit.Refile(Fixtures.headingAnchor(it, "** WAITING Electrician quote"), null) }

	// W/o a target in a file whose first line is a heading, the entry goes before the second heading.
	@Test
	fun refiledFirstPastAHeadingOnTheFirstLine() = check("refile-first-top-heading", "memos.org") { Edit.Refile(Fixtures.anchor(it, Fixtures.FRICK), null) }

	// The refile position last: the end of the target's subtree, one level deeper w/ its children, and
	// the tags of each moved heading realign.
	@Test
	fun refiledLastShiftsItsLevels() = check("outline-refile", "outline.org", Fixtures.writer(OrgSettings.STARTING.copy(refilePosition = Position.LAST))) {
		Edit.Refile(Fixtures.headingAnchor(it, "** TODO Oil change on C3"), Fixtures.headingAnchor(it, "** Condenser retrofit"))
	}

	@Test(expected = IllegalArgumentException::class)
	fun refileIntoItsOwnSubtreeIsRefused() {
		val text = Fixtures.text("outline.org")
		writer.apply(text, Edit.Refile(Fixtures.headingAnchor(text, "* Compressors"), Fixtures.headingAnchor(text, "*** Drain the separator")))
	}

	// Across files: the target is written first, then the source, each matching Org's org-refile.
	@Test
	fun refiledToAnotherFileInTwoHalves() {
		val source = Fixtures.text("outline.org")
		val target = Fixtures.text("memos.org")
		val subtree = writer.subtree(source, Fixtures.headingAnchor(source, "*** Drain the separator"))
		assertNotNull(subtree)
		val into = writer.refileIn(target, Fixtures.anchor(target, Fixtures.MEMO_UNFILED), subtree!!)
		assertEquals(Fixtures.text("refile-across-target.expected.org"), written(into))
		assertEquals(Fixtures.text("refile-across-source.expected.org"), written(writer.refileOut(source, subtree)))
	}

	// A subtree changed in its source after the copy stays there; the target keeps the copy.
	@Test
	fun refileOutOfAChangedSubtreeIsAConflict() {
		val source = Fixtures.text("outline.org")
		val subtree = writer.subtree(source, Fixtures.headingAnchor(source, "** TODO Oil change on C3"))!!
		val changed = source.replace("*** Drain the separator\n", "*** Drain the separator and the pot\n")
		assertEquals(WriteResult.Conflict, writer.refileOut(changed, subtree))
	}

	// A target heading changed since the read takes no copy, and the source keeps the entry.
	@Test
	fun refileIntoAChangedTargetIsAConflict() {
		val source = Fixtures.text("outline.org")
		val target = Fixtures.text("memos.org")
		val subtree = writer.subtree(source, Fixtures.headingAnchor(source, "*** Drain the separator"))!!
		val unfiled = Fixtures.anchor(target, Fixtures.MEMO_UNFILED)
		val changed = target.replace(unfiled.headingLine, unfiled.headingLine + " box")
		assertEquals(WriteResult.Conflict, writer.refileIn(changed, unfiled, subtree))
	}
}
