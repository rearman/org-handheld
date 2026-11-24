package com.rearman.orgpalm.org

import com.rearman.orgpalm.org.Fixtures.ts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY

// Each test applies one edit and compares the text, byte for byte, w/ what Org's own command wrote for
// the same edit (tools/make-fixtures.el).
class OrgWriterTest {
	private val writer = Fixtures.writer()

	private fun written(result: WriteResult): String? = (result as? WriteResult.Written)?.text

	private fun check(case: String, input: String, edit: (String) -> Edit) {
		val text = Fixtures.text(input)
		assertEquals(Fixtures.text("$case.expected.org"), written(writer.apply(text, edit(text))))
	}

	private fun update(case: String, input: String, id: String, vararg changes: FieldChange) =
		check(case, input) { Edit.Update(Fixtures.anchor(it, id), changes.toList()) }

	private fun todo(case: String, id: String, vararg changes: FieldChange) = update(case, "todo.org", id, *changes)

	private fun calendar(case: String, id: String, vararg changes: FieldChange) = update(case, "calendar.org", id, *changes)

	@Test
	fun stateToDoneWritesClosed() = todo("todo-done", Fixtures.VFD, FieldChange.State("DONE"))

	@Test
	fun stateFromDoneRemovesClosed() = todo("todo-reopen", Fixtures.IO_LIST, FieldChange.State("TODO"))

	@Test
	fun stateBetweenOpenKeywords() = todo("todo-next", Fixtures.VFD, FieldChange.State("NEXT"))

	@Test
	fun deadlineChange() = todo("deadline-set", Fixtures.VFD, FieldChange.Deadline(ts("<2026-11-27 Fri>")))

	@Test
	fun deadlineRemoval() = todo("deadline-remove", Fixtures.VFD, FieldChange.Deadline(null))

	@Test
	fun scheduleAddedDropsClosedAsOrgDoes() = todo("schedule-add", Fixtures.IO_LIST, FieldChange.Scheduled(ts("<2026-11-30 Mon>")))

	@Test
	fun scheduleRemoval() = todo("schedule-remove", Fixtures.VFD, FieldChange.Scheduled(null))

	@Test
	fun priorityChange() = todo("priority-set", Fixtures.IO_LIST, FieldChange.Priority("A"))

	@Test
	fun priorityRemovalRealignsTags() = todo("priority-remove", Fixtures.VFD, FieldChange.Priority(null))

	@Test
	fun tagsAddedAlignToColumn77() = todo("tags-set", Fixtures.IO_LIST, FieldChange.Tags(listOf("work", "plc")))

	@Test
	fun tagsCleared() = todo("tags-clear", Fixtures.VFD, FieldChange.Tags(emptyList()))

	@Test
	fun titleChangeRealignsTags() = todo("title-edit", Fixtures.VFD, FieldChange.Title("Order VFD spares & fuses"))

	@Test
	fun bodyChange() = todo("body-edit", Fixtures.VFD, FieldChange.Body("Two ACS580s, frame R3.\nCheck stock first."))

	@Test
	fun taskInsertedAtTheEndOfItsParent() = check("task-append", "todo.org") {
		Edit.Insert(
			Fixtures.anchor(it, Fixtures.UNFILED),
			listOf(FieldChange.Title("Order spare contactors"), FieldChange.State("TODO"), FieldChange.Deadline(ts("<2026-12-01 Tue>"))),
		)
	}

	@Test
	fun taskInsertedAheadOfTheNextHeading() = check("task-append-middle", "todo.org") {
		Edit.Insert(Fixtures.anchor(it, Fixtures.WORK), listOf(FieldChange.Title("Check relief valves")))
	}

	@Test
	fun taskDeletedWithItsSpan() = check("task-delete", "todo.org") { Edit.Delete(Fixtures.anchor(it, Fixtures.IO_LIST)) }

	@Test
	fun warnTimeAdded() = calendar("warn-set", Fixtures.OFFICE, FieldChange.warnMinutes(30))

	@Test
	fun warnTimeChanged() = calendar("warn-change", Fixtures.PM, FieldChange.warnMinutes(45))

	@Test
	fun warnTimeRemoved() = calendar("warn-remove", Fixtures.PM, FieldChange.warnMinutes(null))

	@Test
	fun timestampChanged() = calendar("timestamp-change", Fixtures.PM, FieldChange.Timestamps(listOf(ts("<2026-11-24 Tue 10:00-11:30>"))))

	@Test
	fun weekdaySetWritten() = calendar(
		"weekdays-add", Fixtures.PM,
		FieldChange.Timestamps(OrgFile.weekdayTimestamps(ts("<2026-11-23 Mon 09:00-10:00 +1w>"), setOf(MONDAY, THURSDAY))),
	)

	@Test
	fun weekdaySetNarrowed() = calendar(
		"weekdays-remove", Fixtures.WALKDOWN,
		FieldChange.Timestamps(OrgFile.weekdayTimestamps(ts("<2026-11-23 Mon 07:00-08:00 +2w>"), setOf(MONDAY))),
	)

	@Test
	fun repeaterChangedOnEveryTimestamp() = calendar(
		"repeater-change", Fixtures.WALKDOWN,
		FieldChange.Timestamps(OrgFile.weekdayTimestamps(ts("<2026-11-23 Mon 07:00-08:00 +1w>"), setOf(MONDAY, THURSDAY))),
	)

	@Test
	fun eventTagged() = calendar("event-tags", Fixtures.PM, FieldChange.Tags(listOf(PRIVATE)))

	@Test
	fun eventTitleChanged() = calendar("event-title", Fixtures.OFFICE, FieldChange.Title("Office closed for Thanksgiving"))

	@Test
	fun eventInsertedAtTheEndOfTheFile() = check("event-append", "calendar.org") {
		Edit.Insert(
			null,
			listOf(FieldChange.Title("Ammonia leak drill"), FieldChange.warnMinutes(30), FieldChange.Timestamps(listOf(ts("<2026-12-03 Thu 13:30-14:30>")))),
		)
	}

	@Test
	fun eventDeletedWithItsSpan() = check("event-delete", "calendar.org") { Edit.Delete(Fixtures.anchor(it, Fixtures.OFFICE)) }

	@Test
	fun crlfFileKeepsItsLineEndings() = update("crlf-done", "crlf.org", Fixtures.GASKET, FieldChange.State("DONE"))

	@Test
	fun logbookStaysOnStateChange() = update("logbook-done", "logbook.org", Fixtures.TRANSMITTER, FieldChange.State("DONE"))

	@Test
	fun logbookStaysOnBodyChange() =
		update("logbook-body", "logbook.org", Fixtures.TRANSMITTER, FieldChange.Body("Use the Fluke 754.\nZero at 0 psig."))

	@Test
	fun foreignPropertiesStayInPlace() = update("foreign-warn", "foreign.org", Fixtures.VENDOR, FieldChange.warnMinutes(20))

	@Test
	fun childHeadingsGoWithADeletedEntry() = check("children-delete", "children.org") { Edit.Delete(Fixtures.anchor(it, Fixtures.REBUILD)) }

	@Test
	fun childHeadingsStayOnATitleChange() =
		update("children-title", "children.org", Fixtures.REBUILD, FieldChange.Title("Rebuild compressor 3, RWF II"))

	@Test
	fun noteTitleChanged() = update("memo-title", "memos.org", Fixtures.FRICK, FieldChange.Title("Frick rep, RWF II"))

	// Org ends the new heading line w/ a newline, though the file had none.
	@Test
	fun noteInsertedIntoAFileWithoutAFinalNewline() = check("memo-append", "no-final-newline.org") {
		Edit.Insert(Fixtures.anchor(it, Fixtures.NF_UNFILED), listOf(FieldChange.Title("Gate codes")))
	}

	@Test
	fun unchangedValuesLeaveTheTextAlone() {
		val text = Fixtures.text("todo.org")
		val same = listOf(
			FieldChange.Title("Order VFD spares"), FieldChange.State("TODO"), FieldChange.Priority("A"),
			FieldChange.Tags(listOf(PRIVATE)), FieldChange.Deadline(ts("<2026-11-25 Wed>")),
			FieldChange.Scheduled(ts("<2026-11-23 Mon>")), FieldChange.Body("Two ACS580s, frame R3."),
		)
		assertEquals(text, written(writer.apply(text, Edit.Update(Fixtures.anchor(text, Fixtures.VFD), same))))
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

	// An unreadable planning stamp locks its own keyword alone.
	@Test
	fun lockedPlanningStampRefusesItsKeywordAlone() {
		val text = "* TODO Third Wednesday check\nSCHEDULED: <%%(diary-float t 3 3)>\n"
		val anchor = OrgFile.parse("", text).entries.single().anchor
		assertEquals(WriteResult.Locked, writer.apply(text, Edit.Update(anchor, listOf(FieldChange.Scheduled(ts("<2026-12-02 Wed>"))))))
		val due = written(writer.apply(text, Edit.Update(anchor, listOf(FieldChange.Deadline(ts("<2026-12-04 Fri>"))))))
		assertEquals("* TODO Third Wednesday check\nDEADLINE: <2026-12-04 Fri> SCHEDULED: <%%(diary-float t 3 3)>\n", due)
	}

	@Test(expected = IllegalArgumentException::class)
	fun priorityOutsideTheFileRangeIsRefused() {
		val text = Fixtures.text("todo.org")
		writer.apply(text, Edit.Update(Fixtures.anchor(text, Fixtures.VFD), listOf(FieldChange.Priority("D"))))
	}

	// Marking a repeating task done: org-auto-repeat-maybe.
	private fun repeat(case: String, id: String) = update(case, "repeat.org", id, FieldChange.State("DONE"))

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

	@Test
	fun scheduleAddedBesideADeadline() =
		update("schedule-beside-deadline", "children.org", Fixtures.REBUILD, FieldChange.Scheduled(ts("<2026-12-02 Wed>")))

	@Test
	fun deadlineAddedBesideASchedule() =
		update("deadline-beside-schedule", "logbook.org", Fixtures.TRANSMITTER, FieldChange.Deadline(ts("<2026-11-28 Sat>")))

	@Test
	fun lastPropertyDeletedTakesTheDrawer() =
		update("drawer-remove", "children.org", Fixtures.ORDER_GASKETS, FieldChange.Property("ID", null))

	@Test
	fun bomFileTitleChanged() = update("bom-title", "bom.org", Fixtures.BOM_NOTE, FieldChange.Title("Notes"))

	@Test
	fun bomFileInsertedInto() = check("bom-append", "bom.org") { Edit.Insert(null, listOf(FieldChange.Title("Second"))) }

	@Test
	fun mixedFileEdited() = update("mixed-warn", "mixed.org", Fixtures.MIXED, FieldChange.warnMinutes(10))

	@Test
	fun mixedFileInsertedInto() = check("mixed-append", "mixed.org") { Edit.Insert(null, listOf(FieldChange.Title("B"))) }

	@Test
	fun emptyFileInsertedInto() = check("empty-append", "empty.org") {
		Edit.Insert(null, listOf(FieldChange.Title("First event"), FieldChange.Timestamps(listOf(ts("<2026-12-01 Tue>")))))
	}

	@Test
	fun taskRefiledToTheEndOfAnotherHeading() =
		check("task-move", "todo.org") { Edit.Refile(Fixtures.anchor(it, Fixtures.VFD), Fixtures.anchor(it, Fixtures.UNFILED)) }

	@Test
	fun noteRefiledIntoAMiddleHeading() =
		check("note-move-middle", "move.org") { Edit.Refile(Fixtures.anchor(it, Fixtures.FORKLIFT), Fixtures.anchor(it, Fixtures.MOVE_WORK)) }

	@Test
	fun noteRefiledWithItsTrailingBlankLine() =
		check("note-move-end", "move.org") { Edit.Refile(Fixtures.anchor(it, Fixtures.PUMP), Fixtures.anchor(it, Fixtures.MOVE_SHOP)) }

	// The log note skips the blank line after the drawer and goes ahead of the next heading.
	@Test
	fun repeatWithoutAKeywordLogsAnEmptyOldState() = update("repeat-no-keyword", "repeat2.org", Fixtures.SWEEP, FieldChange.State("DONE"))

	// The note skips the blank line and goes ahead of the body text.
	@Test
	fun repeatHabitKeepsItsSuffix() = update("repeat-habit", "repeat2.org", Fixtures.STRETCH, FieldChange.State("DONE"))

	// The last step of a repeat drops a "--2d" delay, as org-timestamp-change w/ suppress-tmp-delay does.
	@Test
	fun repeatDropsADelay() = repeat("repeat-delay", Fixtures.DELAYED)

	// The first repeating stamp has a count of zero, so Org repeats naught and writes CLOSED.
	@Test
	fun zeroCountFirstStampRepeatsNaught() = repeat("repeat-zero-first", Fixtures.ZERO_FIRST)

	// A repeating stamp in the body moves on w/ the planning line.
	@Test
	fun repeatMovesABodyStamp() = repeat("repeat-body-stamp", Fixtures.SITE_VISIT)

	@Test
	fun plusPlusZeroStaysPut() {
		val text = "* Work\n** TODO Check\nDEADLINE: <2026-11-20 Fri +1w> SCHEDULED: <2026-11-18 Wed ++0d>\n:PROPERTIES:\n:ID:       h\n:END:\n"
		assertTrue(markDone(text).contains("DEADLINE: <2026-11-27 Fri +1w> SCHEDULED: <2026-11-18 Wed ++0d>"))
	}

	// A :REPEAT_TO_STATE: that names no keyword of the file counts for naught.
	@Test
	fun repeatIgnoresAnUnknownRepeatToState() = repeat("repeat-to-unknown-state", Fixtures.GAUGE)

	@Test
	fun repeatGoesToRepeatToState() = update("repeat-to-state", "repeat2.org", Fixtures.ROUNDS, FieldChange.State("DONE"))

	// A #+TYP_TODO: state stays; at the end of the file the note follows the blank line w/o a newline.
	@Test
	fun repeatKeepsATypedState() = update("repeat-typed", "repeat2.org", Fixtures.ANNA, FieldChange.State("FINISHED"))

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
	fun dotRepeatOfZeroMovesToTodayBesideALiveRepeater() {
		val text = "* Work\n** TODO Check\nDEADLINE: <2026-11-20 Fri +1w> SCHEDULED: <2026-11-18 Wed .+0d>\n:PROPERTIES:\n:ID:       h\n:END:\n"
		assertTrue(markDone(text).contains("DEADLINE: <2026-11-27 Fri +1w> SCHEDULED: <2026-11-23 Mon .+0d>"))
	}

	// Any layout: a level-1 and a level-3 heading of a file of mixed levels, w/o IDs.
	@Test
	fun levelOneHeadingEdited() =
		check("outline-tags-top", "outline.org") { Edit.Update(Fixtures.headingAnchor(it, "* Compressors"), listOf(FieldChange.Tags(listOf("mech", "shop")))) }

	@Test
	fun levelThreeHeadingEdited() =
		check("outline-done-deep", "outline.org") { Edit.Update(Fixtures.headingAnchor(it, "*** NEXT Order filters"), listOf(FieldChange.State("DONE"))) }

	// The new heading goes after the parent's child, and Written names its line.
	@Test
	fun insertedUnderADeepHeading() {
		val text = Fixtures.text("outline.org")
		val edit = Edit.Insert(Fixtures.headingAnchor(text, "*** TODO Submittal review"), listOf(FieldChange.Title("Check breaker sizes"), FieldChange.State("TODO")))
		assertEquals(WriteResult.Written(Fixtures.text("outline-insert-deep.expected.org"), 32), writer.apply(text, edit))
	}

	// The subtree goes one level deeper w/ its children, and the tags of each moved heading realign.
	@Test
	fun refiledSubtreeShiftsItsLevels() = check("outline-refile", "outline.org") {
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

	@Test
	fun fileWithConflictMarkersRefusesEveryEdit() {
		val text = Fixtures.text("conflict.org")
		val shared = Fixtures.headingAnchor(text, "* TODO Shared task")
		assertEquals(WriteResult.ConflictMarkers, writer.apply(text, Edit.Update(shared, listOf(FieldChange.State("DONE")))))
		assertEquals(WriteResult.ConflictMarkers, writer.apply(text, Edit.Insert(null, listOf(FieldChange.Title("New")))))
		assertEquals(WriteResult.ConflictMarkers, writer.apply(text, Edit.Delete(shared)))
		val subtree = writer.subtree(text, shared)!!
		assertEquals(WriteResult.ConflictMarkers, writer.refileIn(text, null, subtree))
		assertEquals(WriteResult.ConflictMarkers, writer.refileOut(text, subtree))
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

	// The user's settings (OrgSettings.STARTING), as make-fixtures.el sets init.el's values.
	private val user = Fixtures.writer(OrgSettings.STARTING)

	private fun userCase(case: String, edit: (String) -> Edit) {
		val text = Fixtures.text("user.org")
		assertEquals(Fixtures.text("$case.expected.org"), written(user.apply(text, edit(text))))
	}

	private fun userUpdate(case: String, prefix: String, vararg changes: FieldChange) =
		userCase(case) { Edit.Update(Fixtures.headingAnchor(it, prefix), changes.toList()) }

	// DONE(d!): CLOSED per org-log-done, and a state line after the drawers.
	@Test
	fun doneLogsClosedAndAStateLine() = userUpdate("user-done", "** NEXT Order the fan motor", FieldChange.State("DONE"))

	// WAITING(w@/!): the note the form took, below the state line.
	@Test
	fun waitingLogsItsNote() =
		userUpdate("user-waiting-note", "** TODO Pick up the motor", FieldChange.State("WAITING", "Waiting on the vendor quote."))

	@Test
	fun rescheduleLogsTheOldDate() = userUpdate("user-reschedule", "** NEXT Order the fan motor", FieldChange.Scheduled(ts("<2026-11-30 Mon>")))

	// org-tags-column 0: one space before the tags.
	@Test
	fun titleChangeAtTagsColumnZero() =
		userUpdate("user-title", "** TODO Call the vendor", FieldChange.Title("Call the vendor about the gasket kit"))

	// The refile position first: right after the target's own section.
	@Test
	fun refiledFirstUnderItsTarget() = userCase("user-refile") {
		Edit.Refile(Fixtures.headingAnchor(it, "** TODO Hardware store run"), Fixtures.headingAnchor(it, "* Shop renovation"))
	}

	// The refile position first w/o a target: atop the file's first heading, at level 1.
	@Test
	fun refiledFirstAtTheTopLevel() = userCase("user-refile-top") { Edit.Refile(Fixtures.headingAnchor(it, "** WAITING Electrician quote"), null) }

	// A numeric range from the settings: a cookie of 1, and of the default 5, which Org writes too.
	@Test
	fun numericPriorityWritten() = userUpdate("user-priority-set", "** TODO Pick up the motor", FieldChange.Priority("1"))

	@Test
	fun defaultPriorityWritesItsCookie() = userUpdate("user-priority-default", "** TODO Pick up the motor", FieldChange.Priority("5"))

	// An open child blocks DONE, and Emacs leaves the file as it was.
	@Test
	fun openChildBlocksDone() {
		val text = Fixtures.text("user.org")
		val edit = Edit.Update(Fixtures.headingAnchor(text, "* Shop renovation"), listOf(FieldChange.State("DONE")))
		assertEquals(WriteResult.Blocked("\"NEXT Order the fan motor :call:\""), user.apply(text, edit))
		assertEquals(text, Fixtures.text("user-blocked.expected.org"))
	}

	@Test
	fun uncheckedBoxBlocksDone() {
		val text = Fixtures.text("user.org")
		val edit = Edit.Update(Fixtures.headingAnchor(text, "** TODO Hardware store run"), listOf(FieldChange.State("DONE")))
		assertEquals(WriteResult.Blocked("contained checkboxes"), user.apply(text, edit))
		assertEquals(text, Fixtures.text("user-blocked-box.expected.org"))
	}

	// The capture position first, as org-capture w/ :prepend t places an entry.
	private val prepend = Fixtures.writer(OrgSettings.STOCK.copy(capturePosition = Position.FIRST))
	private val vendor = listOf(FieldChange.Title("Call the vendor"), FieldChange.State("TODO"))

	@Test
	fun capturedFirstUnderAHeading() {
		val text = Fixtures.text("outline.org")
		val result = prepend.apply(text, Edit.Insert(Fixtures.headingAnchor(text, "* Projects"), vendor))
		assertEquals(WriteResult.Written(Fixtures.text("capture-first.expected.org"), 24), result)
	}

	// At the top level the blank line before the first heading goes, as org-capture drops it.
	@Test
	fun capturedFirstAtTheTopLevel() {
		val text = Fixtures.text("outline.org")
		assertEquals(WriteResult.Written(Fixtures.text("capture-first-top.expected.org"), 6), prepend.apply(text, Edit.Insert(null, vendor)))
	}

	// org-log-into-drawer: the drawer is made after the planning line and takes the note.
	@Test
	fun logGoesIntoADrawer() {
		val text = Fixtures.text("user.org")
		val edit = Edit.Update(Fixtures.headingAnchor(text, "** TODO Pick up the motor"), listOf(FieldChange.State("DONE")))
		val drawer = Fixtures.writer(OrgSettings.STARTING.copy(logIntoDrawer = OrgSettings.LOGBOOK))
		assertEquals(Fixtures.text("user-drawer.expected.org"), written(drawer.apply(text, edit)))
	}

	// #+STARTUP: logdrawer in the file wins over org-log-into-drawer, ahead of the body text.
	@Test
	fun startupLogdrawerWins() {
		val text = Fixtures.text("startup.org")
		val edit = Edit.Update(Fixtures.headingAnchor(text, "* TODO Fix the door"), listOf(FieldChange.State("DONE")))
		assertEquals(Fixtures.text("startup-drawer.expected.org"), written(writer.apply(text, edit)))
	}

	// WAITING(w@/!) logs on leaving it, above the older note.
	@Test
	fun leavingWaitingLogs() = userUpdate("user-leave-waiting", "** WAITING Electrician quote", FieldChange.State("TODO"))

	@Test
	fun unscheduleLogsTheOldDate() = userUpdate("user-unschedule", "** NEXT Order the fan motor", FieldChange.Scheduled(null))

	// Org removes every "DEADLINE: <...>" of the entry, the body's too (DESIGN.md meant to keep it).
	@Test
	fun deadlineRemovalLogsAndTakesTheBodyStampToo() = userUpdate("user-undeadline", "** TODO Call the vendor", FieldChange.Deadline(null))

	// A date and a state in one save log as two commands do: the state's note above the date's.
	@Test
	fun stateAndDateInOneSave() = userUpdate(
		"user-state-and-date", "** NEXT Order the fan motor",
		FieldChange.State("DONE"), FieldChange.Scheduled(ts("<2026-11-30 Mon>")),
	)

	// DONE(d!) on a repeating task: the state line once, :LAST_REPEAT:, and the date moved on.
	@Test
	fun repeatingTaskUnderTheUsersSettings() = userUpdate("user-repeat", "** NEXT Weekly oil check", FieldChange.State("DONE"))

	@Test
	fun emptyNoteLogsTheLineAlone() = userUpdate("user-empty-note", "** TODO Pick up the motor", FieldChange.State("WAITING", ""))

	// W/o a target in a file whose first line is a heading, the entry goes before the second heading.
	@Test
	fun refiledFirstPastAHeadingOnTheFirstLine() {
		val text = Fixtures.text("memos.org")
		val edit = Edit.Refile(Fixtures.anchor(text, Fixtures.FRICK), null)
		assertEquals(Fixtures.text("refile-first-top-heading.expected.org"), written(user.apply(text, edit)))
	}

	// org-log-done 'note on a repeating task w/o marks: the CLOSING NOTE alone, since
	// org-auto-repeat-maybe finds a note set up already.
	@Test
	fun closingNoteOnARepeatLogsOnce() {
		val text = Fixtures.text("repeat.org")
		val edit = Edit.Update(Fixtures.anchor(text, Fixtures.OIL_CHECK), listOf(FieldChange.State("DONE", "Checked.")))
		val closing = Fixtures.writer(Fixtures.BASE.copy(logDone = LogMode.NOTE))
		assertEquals(Fixtures.text("repeat-closing-note.expected.org"), written(closing.apply(text, edit)))
	}
}
