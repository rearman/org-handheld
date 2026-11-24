package com.rearman.orgpalm.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RebaseTest {
	private val writer = Fixtures.writer()

	// The app read todo.org and edits a title; meanwhile Emacs marked the task DONE.
	@Test
	fun editReappliedAfterAnOutsideEdit() {
		val read = Fixtures.bytes("todo.org")
		val fresh = Fixtures.bytes("todo-done.expected.org")
		assertNotEquals(Rebase.hash(read), Rebase.hash(fresh))
		val edit = Edit.Update(Fixtures.VFD, listOf(FieldChange.Title("Order VFD spares & fuses")))
		val result = Rebase.rebase(edit, String(fresh, Charsets.UTF_8), FileKind.TODO, writer)
		assertEquals(WriteResult.Written(Fixtures.text("rebase-title.expected.org"), Fixtures.VFD), result)
	}

	// The FIPS 180-2 vector for "abc".
	@Test
	fun hashIsSha256InHex() {
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Rebase.hash("abc".toByteArray()))
	}

	// Emacs gave the event a diary sexp, which the app cannot read, while the app edited its title.
	@Test
	fun editOntoARecordMadeReadOnlyIsRefused() {
		val fresh = Fixtures.text("calendar.org").replace("<2026-11-23 Mon 09:00-10:00>", "<%%(diary-float t 1 4)>")
		val edit = Edit.Update(Fixtures.PM, listOf(FieldChange.Title("Compressor PM")))
		assertEquals(WriteResult.ReadOnly(Fixtures.PM), Rebase.rebase(edit, fresh, FileKind.CALENDAR, writer))
	}

	// Emacs deleted the record that the app edits.
	@Test
	fun editOntoADeletedRecordReportsTheLoss() {
		val edit = Edit.Update(Fixtures.IO_LIST, listOf(FieldChange.Priority("A")))
		val result = Rebase.rebase(edit, Fixtures.text("task-delete.expected.org"), FileKind.TODO, writer)
		assertEquals(WriteResult.RecordDeleted(Fixtures.IO_LIST), result)
	}
}
