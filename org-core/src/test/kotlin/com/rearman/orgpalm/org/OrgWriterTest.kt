package com.rearman.orgpalm.org

import com.rearman.orgpalm.org.Fixtures.ts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY

// Each test applies one edit and compares the text, byte for byte, w/ what Org's own command wrote for
// the same edit (tools/make-fixtures.el).
class OrgWriterTest {
	private val writer = Fixtures.writer()

	private fun check(case: String, input: String, kind: FileKind, edit: Edit) {
		val result = writer.apply(Fixtures.text(input), kind, edit)
		val id = when (edit) {
			is Edit.Update -> edit.id
			is Edit.Delete -> edit.id
			is Edit.Move -> edit.id
			is Edit.Append -> Fixtures.NEW_ID
			is Edit.AppendTo -> Fixtures.NEW_ID
		}
		assertEquals(WriteResult.Written(Fixtures.text("$case.expected.org"), id), result)
	}

	private fun todo(case: String, id: String, vararg changes: FieldChange) =
		check(case, "todo.org", FileKind.TODO, Edit.Update(id, changes.toList()))

	private fun calendar(case: String, id: String, vararg changes: FieldChange) =
		check(case, "calendar.org", FileKind.CALENDAR, Edit.Update(id, changes.toList()))

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
	fun categoryRenameWritesHeadingAndProperty() =
		todo("category-rename", Fixtures.WORK, *FieldChange.categoryName("Shop").toTypedArray())

	@Test
	fun taskAppendedAtTheEndOfItsCategory() = check(
		"task-append", "todo.org", FileKind.TODO,
		Edit.Append(
			Fixtures.UNFILED,
			listOf(FieldChange.Title("Order spare contactors"), FieldChange.State("TODO"), FieldChange.Deadline(ts("<2026-12-01 Tue>"))),
		),
	)

	@Test
	fun taskAppendedAheadOfTheNextCategory() = check(
		"task-append-middle", "todo.org", FileKind.TODO,
		Edit.Append(Fixtures.WORK, listOf(FieldChange.Title("Check relief valves"))),
	)

	@Test
	fun taskDeletedWithItsSpan() = check("task-delete", "todo.org", FileKind.TODO, Edit.Delete(Fixtures.IO_LIST))

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
	fun eventAppendedAtTheEndOfTheFile() = check(
		"event-append", "calendar.org", FileKind.CALENDAR,
		Edit.Append(
			null,
			listOf(FieldChange.Title("Ammonia leak drill"), FieldChange.warnMinutes(30), FieldChange.Timestamps(listOf(ts("<2026-12-03 Thu 13:30-14:30>")))),
		),
	)

	@Test
	fun eventDeletedWithItsSpan() = check("event-delete", "calendar.org", FileKind.CALENDAR, Edit.Delete(Fixtures.OFFICE))

	@Test
	fun crlfFileKeepsItsLineEndings() = check("crlf-done", "crlf.org", FileKind.TODO, Edit.Update(Fixtures.GASKET, listOf(FieldChange.State("DONE"))))

	@Test
	fun logbookStaysOnStateChange() =
		check("logbook-done", "logbook.org", FileKind.TODO, Edit.Update(Fixtures.TRANSMITTER, listOf(FieldChange.State("DONE"))))

	@Test
	fun logbookStaysOnBodyChange() = check(
		"logbook-body", "logbook.org", FileKind.TODO,
		Edit.Update(Fixtures.TRANSMITTER, listOf(FieldChange.Body("Use the Fluke 754.\nZero at 0 psig."))),
	)

	@Test
	fun foreignPropertiesStayInPlace() =
		check("foreign-warn", "foreign.org", FileKind.CALENDAR, Edit.Update(Fixtures.VENDOR, listOf(FieldChange.warnMinutes(20))))

	@Test
	fun childHeadingsGoWithADeletedRecord() = check("children-delete", "children.org", FileKind.TODO, Edit.Delete(Fixtures.REBUILD))

	@Test
	fun childHeadingsStayOnATitleChange() = check(
		"children-title", "children.org", FileKind.TODO,
		Edit.Update(Fixtures.REBUILD, listOf(FieldChange.Title("Rebuild compressor 3, RWF II"))),
	)

	@Test
	fun missingIdsAddedToARecordAndACategory() =
		assertEquals(Fixtures.text("missing-ids.expected.org"), writer.addMissingIds(Fixtures.text("noid.org"), FileKind.TODO))

	@Test
	fun everyWriteAddsMissingIds() = check(
		"write-adds-ids", "noid.org", FileKind.TODO,
		Edit.Update(Fixtures.REBUILD, listOf(FieldChange.Title("Rebuild compressor 4"))),
	)

	@Test
	fun noteTitleChanged() =
		check("memo-title", "memos.org", FileKind.MEMOS, Edit.Update(Fixtures.FRICK, listOf(FieldChange.Title("Frick rep, RWF II"))))

	@Test
	fun noteAppendedToAFileWithoutAFinalNewline() = check(
		"memo-append", "no-final-newline.org", FileKind.MEMOS,
		Edit.Append(Fixtures.NF_UNFILED, listOf(FieldChange.Title("Gate codes"))),
	)

	@Test
	fun unchangedValuesLeaveTheTextAlone() {
		val text = Fixtures.text("todo.org")
		val same = listOf(
			FieldChange.Title("Order VFD spares"), FieldChange.State("TODO"), FieldChange.Priority("A"),
			FieldChange.Tags(listOf(PRIVATE)), FieldChange.Deadline(ts("<2026-11-25 Wed>")),
			FieldChange.Scheduled(ts("<2026-11-23 Mon>")), FieldChange.Body("Two ACS580s, frame R3."),
		)
		assertEquals(WriteResult.Written(text, Fixtures.VFD), writer.apply(text, FileKind.TODO, Edit.Update(Fixtures.VFD, same)))
	}

	@Test
	fun unchangedEventValuesLeaveTheTextAlone() {
		val text = Fixtures.text("calendar.org")
		val walkdown = OrgFile.parse(FileKind.CALENDAR, text).events[2]
		val same = listOf(
			FieldChange.Title(walkdown.title), FieldChange.Tags(walkdown.tags), FieldChange.warnMinutes(null),
			FieldChange.Timestamps(walkdown.timestamps), FieldChange.Body(walkdown.body),
		)
		assertEquals(WriteResult.Written(text, Fixtures.WALKDOWN), writer.apply(text, FileKind.CALENDAR, Edit.Update(Fixtures.WALKDOWN, same)))
	}

	// A stamp whose value stays keeps its text, though Org would write it otherwise.
	@Test
	fun unchangedTimestampLinesStayByteForByte() {
		val text = "* E\n:PROPERTIES:\n:ID:       x\n:END:\n<2026-11-23 Monday 7:00 +1w>\n<2026-11-26 Thu 07:00 +1w>\n"
		val stamps = listOf(ts("<2026-11-23 Mon 07:00 +1w>"), ts("<2026-11-27 Fri 07:00 +1w>"))
		val out = writer.apply(text, FileKind.CALENDAR, Edit.Update("x", listOf(FieldChange.Timestamps(stamps))))
		assertEquals(WriteResult.Written(text.replace("<2026-11-26 Thu 07:00 +1w>", "<2026-11-27 Fri 07:00 +1w>"), "x"), out)
	}

	@Test
	fun readOnlyRecordRefusesAnUpdate() {
		val text = Fixtures.text("readonly.org")
		val id = "7b3e0a6d-1c95-4f28-8d4b-6f2a9e1c7d03"
		assertEquals(WriteResult.ReadOnly(id), writer.apply(text, FileKind.CALENDAR, Edit.Update(id, listOf(FieldChange.Title("Shutdown")))))
	}

	@Test(expected = IllegalArgumentException::class)
	fun priorityOutsideTheFileRangeIsRefused() {
		writer.apply(Fixtures.text("todo.org"), FileKind.TODO, Edit.Update(Fixtures.VFD, listOf(FieldChange.Priority("D"))))
	}

	// Marking a repeating task done: org-auto-repeat-maybe.
	private fun repeat(case: String, id: String) =
		check(case, "repeat.org", FileKind.TODO, Edit.Update(id, listOf(FieldChange.State("DONE"))))

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
	fun scheduleAddedBesideADeadline() = check(
		"schedule-beside-deadline", "children.org", FileKind.TODO,
		Edit.Update(Fixtures.REBUILD, listOf(FieldChange.Scheduled(ts("<2026-12-02 Wed>")))),
	)

	@Test
	fun deadlineAddedBesideASchedule() = check(
		"deadline-beside-schedule", "logbook.org", FileKind.TODO,
		Edit.Update(Fixtures.TRANSMITTER, listOf(FieldChange.Deadline(ts("<2026-11-28 Sat>")))),
	)

	@Test
	fun lastPropertyDeletedTakesTheDrawer() = check(
		"drawer-remove", "children.org", FileKind.TODO,
		Edit.Update(Fixtures.ORDER_GASKETS, listOf(FieldChange.Property(FieldChange.ID, null))),
	)

	@Test
	fun bomFileTitleChanged() =
		check("bom-title", "bom.org", FileKind.MEMOS, Edit.Update(Fixtures.BOM_NOTE, listOf(FieldChange.Title("Notes"))))

	@Test
	fun bomFileAppended() = check("bom-append", "bom.org", FileKind.MEMOS, Edit.Append(null, listOf(FieldChange.Title("Second"))))

	@Test
	fun mixedFileEdited() =
		check("mixed-warn", "mixed.org", FileKind.CALENDAR, Edit.Update(Fixtures.MIXED, listOf(FieldChange.warnMinutes(10))))

	@Test
	fun mixedFileAppended() = check("mixed-append", "mixed.org", FileKind.CALENDAR, Edit.Append(null, listOf(FieldChange.Title("B"))))

	@Test
	fun emptyFileAppended() = check(
		"empty-append", "empty.org", FileKind.CALENDAR,
		Edit.Append(null, listOf(FieldChange.Title("First event"), FieldChange.Timestamps(listOf(ts("<2026-12-01 Tue>"))))),
	)

	@Test
	fun taskMovedToTheEndOfAnotherCategory() = check("task-move", "todo.org", FileKind.TODO, Edit.Move(Fixtures.VFD, "Unfiled"))

	@Test
	fun noteMovedIntoAMiddleCategory() = check("note-move-middle", "move.org", FileKind.MEMOS, Edit.Move(Fixtures.FORKLIFT, "Work"))

	@Test
	fun noteMovedWithItsTrailingBlankLine() = check("note-move-end", "move.org", FileKind.MEMOS, Edit.Move(Fixtures.PUMP, "Shop"))

	// The log note skips the blank line after the drawer and goes ahead of the next heading.
	@Test
	fun repeatWithoutAKeywordLogsAnEmptyOldState() =
		check("repeat-no-keyword", "repeat2.org", FileKind.TODO, Edit.Update(Fixtures.SWEEP, listOf(FieldChange.State("DONE"))))

	// The note skips the blank line and goes ahead of the body text.
	@Test
	fun repeatHabitKeepsItsSuffix() =
		check("repeat-habit", "repeat2.org", FileKind.TODO, Edit.Update(Fixtures.STRETCH, listOf(FieldChange.State("DONE"))))

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

	// A level-2 heading ahead of the first category is no record: it gets no ID and does not move.
	@Test
	fun strayHeadingIsNoRecord() {
		val text = "** Stray\n:PROPERTIES:\n:ID:       s\n:END:\n* Work\n:PROPERTIES:\n:ID:       w\n:END:\n"
		assertEquals(WriteResult.NotARecord("s"), writer.apply(text, FileKind.TODO, Edit.Move("s", "Work")))
		val bare = "** Stray\n* Work\n:PROPERTIES:\n:ID:       w\n:END:\n"
		assertEquals(bare, writer.addMissingIds(bare, FileKind.TODO))
	}

	// A :REPEAT_TO_STATE: that names no keyword of the file counts for naught.
	@Test
	fun repeatIgnoresAnUnknownRepeatToState() = repeat("repeat-to-unknown-state", Fixtures.GAUGE)

	@Test
	fun repeatGoesToRepeatToState() =
		check("repeat-to-state", "repeat2.org", FileKind.TODO, Edit.Update(Fixtures.ROUNDS, listOf(FieldChange.State("DONE"))))

	// A #+TYP_TODO: state stays; at the end of the file the note follows the blank line w/o a newline.
	@Test
	fun repeatKeepsATypedState() =
		check("repeat-typed", "repeat2.org", FileKind.TODO, Edit.Update(Fixtures.ANNA, listOf(FieldChange.State("FINISHED"))))

	// One task w/ the scheduled stamp [stamp], marked done at Fixtures.NOW (14:30).
	private fun hourly(stamp: String): String =
		"* Work\n** TODO Check\nSCHEDULED: $stamp\n:PROPERTIES:\n:ID:       h\n:END:\n"

	private fun markDone(text: String): String =
		(writer.apply(text, FileKind.TODO, Edit.Update("h", listOf(FieldChange.State("DONE")))) as WriteResult.Written).text

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

	@Test
	fun appendToAnExistingCategory() = check(
		"task-append-middle", "todo.org", FileKind.TODO,
		Edit.AppendTo(" Work\n", listOf(FieldChange.Title("Check relief valves"))),
	)

	@Test
	fun movedIntoAMissingCategory() = check("move-new-category", "todo.org", FileKind.TODO, Edit.Move(Fixtures.VFD, "Shop"))

	@Test
	fun onlyARecordMoves() {
		assertEquals(WriteResult.NotARecord(Fixtures.WORK), writer.apply(Fixtures.text("todo.org"), FileKind.TODO, Edit.Move(Fixtures.WORK, "Unfiled")))
		assertEquals(
			WriteResult.NotARecord(Fixtures.ORDER_GASKETS),
			writer.apply(Fixtures.text("children.org"), FileKind.TODO, Edit.Move(Fixtures.ORDER_GASKETS, "Work")),
		)
	}

	@Test(expected = IllegalArgumentException::class)
	fun blankCategoryIsRefused() {
		writer.apply(Fixtures.text("todo.org"), FileKind.TODO, Edit.AppendTo(" \n ", listOf(FieldChange.Title("x"))))
	}

	@Test
	fun missingCategoryMadeOnFirstFiling() = check(
		"category-create", "children.org", FileKind.TODO,
		Edit.AppendTo("Unfiled", listOf(FieldChange.Title("Order gaskets"), FieldChange.State("TODO"))),
	)
}
