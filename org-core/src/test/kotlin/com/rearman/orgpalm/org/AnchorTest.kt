package com.rearman.orgpalm.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The anchor of DESIGN.md section 4 rule 3: the app read todo.org, then Emacs changed the file ere the
// save.
class AnchorTest {
	private val writer = Fixtures.writer()
	private val read = Fixtures.text("todo.org")
	private val vfd = Fixtures.anchor(read, Fixtures.VFD)
	private val retitle = listOf(FieldChange.Title("Order VFD spares & fuses"))

	// The text of the VFD task's subtree, as a copy would paste it.
	private val vfdSubtree: String = run {
		val doc = OrgDocument.parse(read)
		val h = doc.headings.single { it.start == vfd.lineIndex }
		doc.lines.subList(h.start, h.subtreeEnd).joinToString("") { it.raw }
	}

	// A line added above moves the heading; the anchor finds it at its new line.
	@Test
	fun headingFoundAfterTheLinesShift() {
		val result = writer.apply("#+TITLE: Tasks\n$read", Edit.Update(vfd, retitle))
		assertEquals(WriteResult.Written("#+TITLE: Tasks\n" + Fixtures.text("title-edit.expected.org"), vfd.lineIndex + 1), result)
	}

	// Emacs marked the task DONE (its heading line) or edited its body (its section).
	@Test
	fun changedHeadingIsAConflict() {
		assertEquals(WriteResult.Conflict, writer.apply(Fixtures.text("todo-done.expected.org"), Edit.Update(vfd, retitle)))
		assertEquals(WriteResult.Conflict, writer.apply(Fixtures.text("body-edit.expected.org"), Edit.Update(vfd, retitle)))
	}

	// The anchor holds the heading's own section, so a change to a child leaves it good.
	@Test
	fun changedChildLeavesTheParentAnchorGood() {
		val children = Fixtures.text("children.org")
		val rebuild = Fixtures.anchor(children, Fixtures.REBUILD)
		val fresh = OrgFile.parse("", Fixtures.text("drawer-remove.expected.org"))
		assertEquals(rebuild.lineIndex, locate(fresh, rebuild)?.start)
	}

	@Test
	fun deletedHeadingIsAConflict() {
		val ioList = Fixtures.anchor(read, Fixtures.IO_LIST)
		assertEquals(WriteResult.Conflict, writer.apply(Fixtures.text("task-delete.expected.org"), Edit.Update(ioList, listOf(FieldChange.Priority("A")))))
	}

	// A copy pasted after the heading leaves it at its own line, the target; a line added above as well
	// leaves two candidates and no way to choose.
	@Test
	fun duplicatedHeadingIsAConflictOnlyWhenItMoved() {
		val copied = read.replace(vfdSubtree, vfdSubtree + vfdSubtree)
		assertEquals(vfd.lineIndex, locate(OrgFile.parse("", copied), vfd)?.start)
		assertNull(locate(OrgFile.parse("", "#+TITLE: Tasks\n$copied"), vfd))
	}

	// Twin headings at the read: Emacs marked the first DONE, so its anchor must not find the second.
	@Test
	fun twinAtTheReadIsAConflictOnceTheHeadingChanged() {
		val twins = "* TODO Call vendor\n* Other\n* TODO Call vendor\n"
		val doc = OrgDocument.parse(twins)
		val first = Anchor.of(doc, doc.headings[0])
		assertEquals(2, first.copies)
		assertNull(locate(OrgFile.parse("", twins.replaceFirst("TODO", "DONE")), first))
	}
}
