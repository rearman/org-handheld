package com.rearman.orgpalm.org

import java.util.Locale

// Where a new or moved entry goes under its target (DESIGN.md section 3, Capture & refile): last, at
// the end of the target's subtree, or first, right after the target's own section (atop the file's first
// heading w/o a target). The refile position mirrors org-reverse-note-order, the capture position the
// :prepend of a capture template.
enum class Position { FIRST, LAST }

// What a log variable asks for: naught, a time stamp, or a note (DESIGN.md section 3).
enum class LogMode { NONE, TIME, NOTE }

// The log marks of one TODO keyword, as org-extract-log-state-settings reads "WAIT(w@/!)": [enter] on
// entry to the state ("@" or "!" after the key), [leave] on leaving it (the mark after the slash).
data class KeywordLog(val enter: LogMode, val leave: LogMode)

// The Emacs variables that govern how Org reads and writes a file (DESIGN.md section 3, Emacs
// settings). The defaults are Org's stock values; STARTING holds the user's starting values. A file's
// own lines win where Org lets them: #+TODO: and its kin over [todoKeywords] (TodoKeywords.of), and the
// log words of #+STARTUP: over the log variables (forFile).
//
// [tagsColumn] is org-tags-column: a negative value ends the tags at that column, zero or more starts
// them there, at least one space after the title. [logIntoDrawer] names the drawer of
// org-log-into-drawer, null for none. The priority values are Org's: a number 0 to 64, or the code of a
// letter A to Z (org-priority-highest, org-priority-lowest, org-priority-default); a file's first
// #+PRIORITIES: line wins (Priorities.of).
data class OrgSettings(
	val todoKeywords: List<TodoSequence> = listOf(TodoSequence.parse("TODO DONE")),
	val tagsColumn: Int = -77,
	val priorityHighest: Int = 'A'.code,
	val priorityLowest: Int = 'C'.code,
	val priorityDefault: Int = 'B'.code,
	val tagsExcludedFromInheritance: Set<String> = emptySet(),
	val logDone: LogMode = LogMode.NONE,
	val logRepeat: LogMode = LogMode.TIME,
	val logReschedule: LogMode = LogMode.NONE,
	val logRedeadline: LogMode = LogMode.NONE,
	val logIntoDrawer: String? = null,
	val logNotesAfterDrawers: Boolean = false,
	val refilePosition: Position = Position.LAST,
	val capturePosition: Position = Position.LAST,
	val enforceTodoDependencies: Boolean = false,
	val enforceCheckboxDependencies: Boolean = false,
) {
	// The priority range of these settings.
	val priorities: Priorities get() = Priorities(priorityHighest, priorityLowest, priorityDefault)

	// These settings w/ the log words of every #+STARTUP: line of [doc] applied in file order, as
	// org-startup-options sets them.
	fun forFile(doc: OrgDocument): OrgSettings {
		var s = this
		for (value in doc.keywordValues("STARTUP")) {
			for (word in OrgDocument.words(value)) s = s.withStartup(word.lowercase(Locale.ROOT))
		}
		return s
	}

	private fun withStartup(word: String): OrgSettings = when (word) {
		"logdone" -> copy(logDone = LogMode.TIME)
		"lognotedone" -> copy(logDone = LogMode.NOTE)
		"nologdone" -> copy(logDone = LogMode.NONE)
		"logrepeat" -> copy(logRepeat = LogMode.TIME)
		"lognoterepeat" -> copy(logRepeat = LogMode.NOTE)
		"nologrepeat" -> copy(logRepeat = LogMode.NONE)
		"logreschedule" -> copy(logReschedule = LogMode.TIME)
		"lognotereschedule" -> copy(logReschedule = LogMode.NOTE)
		"nologreschedule" -> copy(logReschedule = LogMode.NONE)
		"logredeadline" -> copy(logRedeadline = LogMode.TIME)
		"lognoteredeadline" -> copy(logRedeadline = LogMode.NOTE)
		"nologredeadline" -> copy(logRedeadline = LogMode.NONE)
		"logdrawer" -> copy(logIntoDrawer = LOGBOOK)
		"nologdrawer" -> copy(logIntoDrawer = null)
		else -> this
	}

	companion object {
		const val LOGBOOK = "LOGBOOK"

		// The user's values when the app first starts (DESIGN.md section 3, Emacs settings).
		val STARTING = OrgSettings(
			todoKeywords = listOf(TodoSequence.parse("TODO(t) NEXT(n) WAITING(w@/!) | DONE(d!) BELAYED(b@)")),
			tagsColumn = 0,
			priorityHighest = 1,
			priorityLowest = 5,
			priorityDefault = 5,
			tagsExcludedFromInheritance = setOf("undertaking"),
			logDone = LogMode.TIME,
			logRepeat = LogMode.TIME,
			logReschedule = LogMode.TIME,
			logRedeadline = LogMode.TIME,
			logIntoDrawer = null,
			logNotesAfterDrawers = true,
			refilePosition = Position.FIRST,
			capturePosition = Position.FIRST,
			enforceTodoDependencies = true,
			enforceCheckboxDependencies = true,
		)
	}
}
