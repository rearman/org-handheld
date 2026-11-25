package com.rearman.orgpalm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rearman.orgpalm.org.Anchor
import com.rearman.orgpalm.org.Edit
import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.FieldChange
import com.rearman.orgpalm.org.OrgFile
import com.rearman.orgpalm.org.OrgSettings
import com.rearman.orgpalm.org.OrgWriter
import com.rearman.orgpalm.org.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.LocalDateTime

// A parsed file & the document it came from.
data class Loaded(val uri: Uri, val file: OrgFile)

// The outcome of a save, as the sheet shows it (PLAN.md phase 4).
sealed interface Saved {
	data object Done : Saved

	// The heading changed or went since the read; the form keeps the edit.
	data object Conflict : Saved

	// A planning field was locked at the read.
	data object Locked : Saved

	data class Blocked(val reason: String) : Saved

	// A refile across files whose source changed after the copy went into the target.
	data object CopyLeft : Saved

	data class Failed(val message: String) : Saved
}

// The folders, the walk, the parsed files, & the saves behind every screen.
class AppModel(app: Application) : AndroidViewModel(app) {
	private val stores = Stores(app)
	private val storage = Storage(app.contentResolver)
	private val cache = FileCache<Uri>()

	// One walk or save at a time, so that a save never reads a file while a walk replaces it.
	private val busy = Mutex()

	val folders: StateFlow<List<Folder>> = stores.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
	val settings: StateFlow<OrgSettings> = stores.settings.stateIn(viewModelScope, SharingStarted.Eagerly, OrgSettings.STARTING)
	val captureTarget: StateFlow<CaptureTarget> = stores.captureTarget.stateIn(viewModelScope, SharingStarted.Eagerly, CaptureTarget())
	val filters: StateFlow<List<SavedFilter>> = stores.filters.stateIn(viewModelScope, SharingStarted.Eagerly, Filters.STARTING)
	val theme: StateFlow<Theme> = stores.theme.stateIn(viewModelScope, SharingStarted.Eagerly, Theme.SYSTEM)

	private val loadedFlow = MutableStateFlow<List<Loaded>>(emptyList())
	val loaded: StateFlow<List<Loaded>> = loadedFlow

	// The message of the last walk that failed, e.g. on a folder whose permission was revoked.
	private val errorFlow = MutableStateFlow<String?>(null)
	val error: StateFlow<String?> = errorFlow

	init {
		viewModelScope.launch { stores.seed() }
	}

	// Walks every folder & re-reads each file whose size or time changed, under the stored settings.
	fun walk() {
		viewModelScope.launch(Dispatchers.IO) { busy.withLock { walkLocked() } }
	}

	private suspend fun walkLocked() {
		try {
			val settings = stores.settings.first()
			val found = LinkedHashMap<Uri, Found>()
			for (folder in stores.folders.first()) found.putAll(storage.orgFiles(folder))
			cache.refresh(found) { uri, f -> OrgFile.parse(f.path, storage.read(uri), settings) }
			loadedFlow.value = cache.files.map { (uri, file) -> Loaded(uri, file) }
			Alarms.schedule(getApplication<Application>(), loadedFlow.value.map { it.file })
			errorFlow.value = null
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			errorFlow.value = e.message ?: e.toString()
		}
	}

	// Keeps read & write access to a picked folder across restarts, adds it flat, & walks.
	fun addFolder(uri: Uri) {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
		viewModelScope.launch {
			stores.addFolder(uri.toString())
			walk()
		}
	}

	// Drops a folder from the list & gives back its permission, & walks; its files stay on the phone.
	fun removeFolder(folder: Folder) {
		viewModelScope.launch {
			stores.removeFolder(folder.uri)
			release(folder.uri)
			walk()
		}
	}

	// Puts a newly picked folder in the place of [folder], w/ its subfolder switch, & walks.
	fun replaceFolder(folder: Folder, uri: Uri) {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
		viewModelScope.launch {
			stores.replaceFolder(folder.uri, uri.toString())
			if (uri.toString() != folder.uri) release(folder.uri)
			walk()
		}
	}

	private fun release(uri: String) {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		// A permission already gone, e.g. revoked by the user, leaves naught to release.
		runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(uri), flags) }
	}

	fun setRecursive(folder: Folder, recursive: Boolean) {
		viewModelScope.launch {
			stores.setRecursive(folder.uri, recursive)
			walk()
		}
	}

	// Stores the settings & reads every file again under them.
	fun saveSettings(settings: OrgSettings, target: CaptureTarget) {
		viewModelScope.launch {
			stores.saveSettings(settings)
			stores.setCaptureTarget(target)
			busy.withLock {
				cache.clear()
				withContext(Dispatchers.IO) { walkLocked() }
			}
		}
	}

	fun setTheme(theme: Theme) {
		viewModelScope.launch { stores.setTheme(theme) }
	}

	fun saveFilters(filters: List<SavedFilter>) {
		viewModelScope.launch { stores.saveFilters(filters) }
	}

	private fun writer(): OrgWriter = OrgWriter({ LocalDateTime.now() }, settings.value)

	private fun uriOf(file: OrgFile): Uri = loaded.value.firstOrNull { it.file === file }?.uri ?: throw FileNotFoundException(file.path)

	// The file as the last walk read it, by its document: a fresh copy after a save or a re-read.
	fun current(uri: Uri): OrgFile? = loaded.value.firstOrNull { it.uri == uri }?.file

	fun uri(file: OrgFile): Uri? = loaded.value.firstOrNull { it.file === file }?.uri

	// Whether a change of [entry] to [keyword] logs a note (OrgWriter.asksNote).
	fun asksNote(file: OrgFile, entry: Entry, keyword: String?): Boolean {
		val h = file.document.headings.firstOrNull { it.start == entry.anchor.lineIndex } ?: return false
		return writer().asksNote(file, h, keyword)
	}

	// Reads the file fresh, applies [edit], & writes the whole file at once (DESIGN.md section 4 rule 3),
	// then walks, so that the cache holds the written text.
	suspend fun save(file: OrgFile, edit: Edit): Saved = locked {
		val uri = uriOf(file)
		val result = writer().apply(OrgFile.parse(file.path, storage.read(uri), settings.value), edit)
		if (result is WriteResult.Written) write(uri, result.text)
		saved(result)
	}

	// A new entry w/ [changes] under [parent] of [file], or at its top level for none.
	suspend fun capture(file: OrgFile, parent: Anchor?, changes: List<FieldChange>): Saved = save(file, Edit.Insert(parent, changes))

	// The subtree at [anchor] of [file] moved under [target] of [targetFile] (its top level for none):
	// one write within a file; across files the target first, then the source, so that a failure
	// leaves a copy & never loses the entry.
	suspend fun refile(file: OrgFile, anchor: Anchor, targetFile: OrgFile, target: Anchor?): Saved {
		if (targetFile === file) return save(file, Edit.Refile(anchor, target))
		return locked {
			val source = uriOf(file)
			val dest = uriOf(targetFile)
			val w = writer()
			val subtree = w.subtree(storage.read(source), anchor) ?: return@locked Saved.Conflict
			val into = w.refileIn(storage.read(dest), target, subtree)
			if (into !is WriteResult.Written) return@locked saved(into)
			write(dest, into.text)
			when (val out = w.refileOut(storage.read(source), subtree)) {
				is WriteResult.Written -> {
					write(source, out.text)
					Saved.Done
				}
				WriteResult.Conflict -> Saved.CopyLeft
				else -> saved(out)
			}
		}
	}

	// Creates [name] in [folder] on the user's request, & walks.
	suspend fun createFile(folder: Folder, name: String): Saved = locked {
		storage.create(folder, name) ?: return@locked Saved.Failed("The folder refused a new file $name")
		Saved.Done
	}

	// Walks after the body, so that a re-read follows every write; an exception becomes Failed, w/
	// the writer's own refusals (an hourly repeat w/o a time) among them.
	private suspend fun locked(body: suspend () -> Saved): Saved = withContext(Dispatchers.IO) {
		busy.withLock {
			val out = try {
				body()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Saved.Failed(e.message ?: e.toString())
			}
			walkLocked()
			out
		}
	}

	private fun write(uri: Uri, text: String) {
		storage.write(uri, text)
		cache.forget(uri)
	}

	private fun saved(result: WriteResult): Saved = when (result) {
		is WriteResult.Written -> Saved.Done
		WriteResult.Conflict -> Saved.Conflict
		WriteResult.Locked -> Saved.Locked
		is WriteResult.Blocked -> Saved.Blocked(result.reason)
	}
}
