package com.rearman.orgpalm.org

import java.time.LocalDateTime

// The fixture files of src/test/resources, and the fixed clock that tools/make-fixtures.el gives Emacs.
object Fixtures {
	val NOW: LocalDateTime = LocalDateTime.of(2026, 11, 23, 14, 30)

	// The hand-made inputs.
	val INPUTS = listOf(
		"calendar.org", "todo.org", "memos.org", "crlf.org", "foreign.org", "logbook.org", "children.org",
		"no-final-newline.org", "readonly.org", "bom.org", "mixed.org", "repeat.org", "empty.org", "move.org",
		"repeat2.org", "outline.org", "user.org", "startup.org",
	)

	// The cases whose <case>.expected.org Emacs writes.
	val CASES = listOf(
		"todo-done", "todo-reopen", "todo-next", "deadline-set", "deadline-remove", "schedule-add",
		"schedule-remove", "priority-set", "priority-remove", "tags-set", "tags-clear", "title-edit", "body-edit",
		"task-append", "task-append-middle", "task-delete", "warn-set", "warn-change", "warn-remove",
		"timestamp-change", "weekdays-add", "weekdays-remove", "repeater-change", "event-tags", "event-title",
		"event-append", "event-delete", "crlf-done", "logbook-done", "logbook-body", "foreign-warn",
		"children-delete", "children-title", "memo-title", "memo-append", "escape-title", "escape-keyword",
		"repeat-plus", "repeat-dot", "repeat-plusplus", "repeat-month-end", "repeat-plain-scheduled",
		"repeat-warning", "schedule-beside-deadline", "deadline-beside-schedule", "drawer-remove", "bom-title",
		"bom-append", "mixed-warn", "mixed-append", "empty-append", "task-move", "note-move-middle",
		"note-move-end", "repeat-no-keyword", "repeat-habit", "repeat-to-state", "repeat-typed", "repeat-delay",
		"repeat-to-unknown-state", "repeat-zero-first", "repeat-body-stamp", "outline-tags-top",
		"outline-done-deep", "outline-insert-deep", "outline-refile", "refile-across-source",
		"refile-across-target", "capture-first", "capture-first-top", "user-done", "user-waiting-note",
		"user-reschedule", "user-title", "user-refile", "user-refile-top", "user-blocked", "user-blocked-box",
		"user-priority-set", "user-priority-default", "user-drawer", "user-leave-waiting", "user-unschedule",
		"user-undeadline", "user-state-and-date", "user-repeat", "user-empty-note", "refile-first-top-heading",
		"startup-drawer", "repeat-closing-note",
	)

	fun bytes(name: String): ByteArray =
		Fixtures::class.java.getResourceAsStream("/$name")?.use { it.readBytes() } ?: error("No fixture $name")

	fun text(name: String): String = String(bytes(name), Charsets.UTF_8)

	// A fixture parsed under its own name, which gives the category of last resort.
	fun file(name: String, settings: OrgSettings = OrgSettings.STOCK): OrgFile = OrgFile.parse(name, text(name), settings)

	// The values under which make-fixtures.el runs its first cases: Org's stock values, save
	// org-log-done 'time, which those cases have always set.
	val BASE = OrgSettings(logDone = LogMode.TIME)

	fun writer(settings: OrgSettings = BASE): OrgWriter = OrgWriter({ NOW }, settings)

	fun ts(text: String): Timestamp = Timestamp.parse(text) ?: error("Not a timestamp: $text")

	// The entry of [text] whose :ID: is [id]. The IDs only label the fixture headings; org-core
	// writes none.
	fun entry(text: String, id: String): Entry = OrgFile.parse("", text).entries.single { it.property("ID") == id }

	fun anchor(text: String, id: String): Anchor = entry(text, id).anchor

	// The anchor of the one heading of [text] whose line begins w/ [prefix].
	fun headingAnchor(text: String, prefix: String): Anchor =
		OrgFile.parse("", text).entries.single { it.anchor.headingLine.startsWith(prefix) }.anchor

	// The :ID:s of the fixture headings.
	const val PM = "6f1d2c4e-8a1b-4c55-9e0a-2b7f1d9c3a10"
	const val OFFICE = "0b7e3a52-41d9-4f0e-a3c6-7d1e9b2f4c88"
	const val WALKDOWN = "9a4c0e1f-3b27-4d8e-b5a1-6c2f8e7d0b43"
	const val WORK = "c3e8b1a0-5d47-4f2a-9e6b-1a7c0d3f8e52"
	const val VFD = "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37"
	const val IO_LIST = "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04"
	const val UNFILED = "e1f6c9b3-2a85-4d07-8b4c-0d3e7a5f1b96"
	const val FRICK = "a2d5f8c1-6e39-4b07-9c4a-3f1b7e0d2c65"
	const val MEMO_UNFILED = "d8a3e6f0-7b12-4c95-8e4d-5a0f2b9c1e73"
	const val GASKET = "b85d1e3a-6c07-4f29-9a4e-0d7f2c8b1e53"
	const val VENDOR = "e4b7a1c9-0d52-4e8f-b3a6-7c1d9f2e0a85"
	const val TRANSMITTER = "6a2f9e0b-4d71-4c53-b8e2-1f0c7a9d3e58"
	const val REBUILD = "0f9c2a7e-3d58-4b16-a7c9-5e1b8d4f2a60"
	const val ORDER_GASKETS = "5b8c2e7f-9a14-4d63-b0e7-6f3a1c9d2e85"
	const val NF_UNFILED = "5d0a8c3f-7e21-4b94-a6d8-3c9f1e7b0a42"
	const val BOM_NOTE = "9e2c4f7a-6b03-4d18-a5e9-1c8f0b3d7a64"
	const val MIXED = "4a1f7c0e-2b96-4d38-8e5a-7c3d0f9b6e21"
	const val SHUTDOWN = "7b3e0a6d-1c95-4f28-8d4b-6f2a9e1c7d03"
	const val OIL_CHECK = "a1c4e7f0-2b5d-4893-b6e9-c2f5a8d1b4e7"
	const val FILTER = "b2d5f8a1-3c6e-4904-87fa-d3a6b9e2c5f8"
	const val REPORT = "c3e6a9b2-4d7f-4a15-98ab-e4b7c0f3d6a9"
	const val VALVE_TEST = "d4f7b0c3-5e80-4b26-a9bc-f5c8d1a4e7b0"
	const val LOG_REVIEW = "e5a8c1d4-6f91-4c37-bacd-a6d9e2b5f8c1"
	const val SENSOR = "f6b9d2e5-7a02-4d48-8bde-b7eaf3c6a9d2"
	const val MOVE_WORK = "71c3e9a5-0d26-4b84-9f17-2a6e8c4b0d39"
	const val MOVE_SHOP = "93e50bc7-2f48-4da6-91b9-4c80ae6d2f5b"
	const val PUMP = "82d4fab6-1e37-4c95-80a8-3b7f9d5c1e4a"
	const val FORKLIFT = "a4f61cd8-3059-4eb7-a2ca-5d91bf7e306c"
	const val SWEEP = "28b4d6fa-1c3e-4057-9b2d-4f6c8e0a2b3d"
	const val STRETCH = "39c5e70b-2d4f-4168-8c3e-5a7d9f1b3c4e"
	const val ROUNDS = "4ad6f81c-3e50-4279-9d4f-6b8e0a2c4d5f"
	const val ANNA = "5be70a2d-4f61-438a-8e50-7c9f1b3d5e6a"
	const val DELAYED = "07c1e4a8-9b2f-4d63-a5e8-1c4f7b0d3e96"
	const val GAUGE = "18d2f5b9-0c3a-4e74-b6f9-2d5a8c1e4fa7"
	const val ZERO_FIRST = "29e3a6c0-1d4b-4f85-a7b0-3e6b9d2f5ab8"
	const val SITE_VISIT = "3af4b7d1-2e5c-4096-b8c1-4f7cae3a6bc9"
}
