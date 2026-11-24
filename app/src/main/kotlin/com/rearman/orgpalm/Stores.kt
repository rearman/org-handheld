package com.rearman.orgpalm

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearman.orgpalm.org.KeywordLog
import com.rearman.orgpalm.org.LogMode
import com.rearman.orgpalm.org.OrgSettings
import com.rearman.orgpalm.org.Position
import com.rearman.orgpalm.org.TodoSequence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("org-palm")

// The stored Emacs settings & the picked folders. The settings are seeded w/ OrgSettings.STARTING on
// the first start; every parse reads them.
class Stores(private val context: Context) {
	val settings: Flow<OrgSettings> = context.store.data.map { SettingsCodec.read(it) }

	val folders: Flow<List<Folder>> = context.store.data.map { p ->
		(p[FOLDERS] ?: emptySet()).map { Folder(it.substring(2), it.startsWith(RECURSIVE)) }.sortedBy { it.uri }
	}

	suspend fun seed() {
		context.store.edit { if (it[SettingsCodec.TODO_KEYWORDS] == null) SettingsCodec.write(it, OrgSettings.STARTING) }
	}

	// Adds a folder, flat, or keeps the one already there.
	suspend fun addFolder(uri: String) {
		context.store.edit { p ->
			val set = p[FOLDERS] ?: emptySet()
			if (set.none { it.substring(2) == uri }) p[FOLDERS] = set + (FLAT + uri)
		}
	}

	suspend fun setRecursive(uri: String, recursive: Boolean) {
		context.store.edit { p ->
			p[FOLDERS] = (p[FOLDERS] ?: emptySet()).map { if (it.substring(2) == uri) (if (recursive) RECURSIVE else FLAT) + uri else it }.toSet()
		}
	}

	private companion object {
		// Each folder is stored as its mark & its tree URI.
		val FOLDERS = stringSetPreferencesKey("folders")
		const val FLAT = "F "
		const val RECURSIVE = "R "
	}
}

// OrgSettings to & from the stored preferences, one key per field. A key not stored reads as the
// starting value.
object SettingsCodec {
	val TODO_KEYWORDS = stringPreferencesKey("todo_keywords")
	private val TAGS_COLUMN = intPreferencesKey("tags_column")
	private val PRIORITY_HIGHEST = intPreferencesKey("priority_highest")
	private val PRIORITY_LOWEST = intPreferencesKey("priority_lowest")
	private val PRIORITY_DEFAULT = intPreferencesKey("priority_default")
	private val TAGS_EXCLUDED = stringSetPreferencesKey("tags_excluded_from_inheritance")
	private val LOG_DONE = stringPreferencesKey("log_done")
	private val LOG_REPEAT = stringPreferencesKey("log_repeat")
	private val LOG_RESCHEDULE = stringPreferencesKey("log_reschedule")
	private val LOG_REDEADLINE = stringPreferencesKey("log_redeadline")
	// The drawer's name; an empty value stands for none.
	private val LOG_INTO_DRAWER = stringPreferencesKey("log_into_drawer")
	private val LOG_NOTES_AFTER_DRAWERS = booleanPreferencesKey("log_notes_after_drawers")
	private val REFILE_POSITION = stringPreferencesKey("refile_position")
	private val CAPTURE_POSITION = stringPreferencesKey("capture_position")
	private val ENFORCE_TODO = booleanPreferencesKey("enforce_todo_dependencies")
	private val ENFORCE_CHECKBOX = booleanPreferencesKey("enforce_checkbox_dependencies")

	fun read(p: Preferences): OrgSettings {
		val s = OrgSettings.STARTING
		return OrgSettings(
			todoKeywords = p[TODO_KEYWORDS]?.let { keywords(it) } ?: s.todoKeywords,
			tagsColumn = p[TAGS_COLUMN] ?: s.tagsColumn,
			priorityHighest = p[PRIORITY_HIGHEST] ?: s.priorityHighest,
			priorityLowest = p[PRIORITY_LOWEST] ?: s.priorityLowest,
			priorityDefault = p[PRIORITY_DEFAULT] ?: s.priorityDefault,
			tagsExcludedFromInheritance = p[TAGS_EXCLUDED] ?: s.tagsExcludedFromInheritance,
			logDone = p[LOG_DONE]?.let { LogMode.valueOf(it) } ?: s.logDone,
			logRepeat = p[LOG_REPEAT]?.let { LogMode.valueOf(it) } ?: s.logRepeat,
			logReschedule = p[LOG_RESCHEDULE]?.let { LogMode.valueOf(it) } ?: s.logReschedule,
			logRedeadline = p[LOG_REDEADLINE]?.let { LogMode.valueOf(it) } ?: s.logRedeadline,
			logIntoDrawer = p[LOG_INTO_DRAWER]?.let { it.ifEmpty { null } } ?: s.logIntoDrawer,
			logNotesAfterDrawers = p[LOG_NOTES_AFTER_DRAWERS] ?: s.logNotesAfterDrawers,
			refilePosition = p[REFILE_POSITION]?.let { Position.valueOf(it) } ?: s.refilePosition,
			capturePosition = p[CAPTURE_POSITION]?.let { Position.valueOf(it) } ?: s.capturePosition,
			enforceTodoDependencies = p[ENFORCE_TODO] ?: s.enforceTodoDependencies,
			enforceCheckboxDependencies = p[ENFORCE_CHECKBOX] ?: s.enforceCheckboxDependencies,
		)
	}

	fun write(p: MutablePreferences, s: OrgSettings) {
		p[TODO_KEYWORDS] = keywordsText(s.todoKeywords)
		p[TAGS_COLUMN] = s.tagsColumn
		p[PRIORITY_HIGHEST] = s.priorityHighest
		p[PRIORITY_LOWEST] = s.priorityLowest
		p[PRIORITY_DEFAULT] = s.priorityDefault
		p[TAGS_EXCLUDED] = s.tagsExcludedFromInheritance
		p[LOG_DONE] = s.logDone.name
		p[LOG_REPEAT] = s.logRepeat.name
		p[LOG_RESCHEDULE] = s.logReschedule.name
		p[LOG_REDEADLINE] = s.logRedeadline.name
		p[LOG_INTO_DRAWER] = s.logIntoDrawer ?: ""
		p[LOG_NOTES_AFTER_DRAWERS] = s.logNotesAfterDrawers
		p[REFILE_POSITION] = s.refilePosition.name
		p[CAPTURE_POSITION] = s.capturePosition.name
		p[ENFORCE_TODO] = s.enforceTodoDependencies
		p[ENFORCE_CHECKBOX] = s.enforceCheckboxDependencies
	}

	// The sequences as the lines of a file would set them, one #+TODO: or #+TYP_TODO: line each, w/ the
	// log marks of each keyword & a "|" before the done ones. A fast-access key is not kept, since
	// TodoSequence drops it.
	fun keywordsText(sequences: List<TodoSequence>): String = sequences.joinToString("\n") { s ->
		val word = { name: String -> name + (s.logs[name]?.let { marks(it) } ?: "") }
		val open = s.names.filter { it !in s.done }.map(word)
		val done = s.done.map(word)
		(if (s.type) TYP_TODO else TODO) + (open + "|" + done).joinToString(" ")
	}

	fun keywords(text: String): List<TodoSequence> = text.lines().filter { it.isNotBlank() }.map {
		if (it.startsWith(TYP_TODO)) TodoSequence.parse(it.removePrefix(TYP_TODO), true) else TodoSequence.parse(it.removePrefix(TODO))
	}

	private const val TODO = "#+TODO: "
	private const val TYP_TODO = "#+TYP_TODO: "

	private fun mark(mode: LogMode): String = when (mode) {
		LogMode.NONE -> ""
		LogMode.TIME -> "!"
		LogMode.NOTE -> "@"
	}

	private fun marks(log: KeywordLog): String =
		"(" + mark(log.enter) + (if (log.leave != LogMode.NONE) "/" + mark(log.leave) else "") + ")"
}
