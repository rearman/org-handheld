package com.rearman.orgpalm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rearman.orgpalm.org.OrgFile
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

// The folders, the walk, & the parsed files behind every screen.
class AppModel(app: Application) : AndroidViewModel(app) {
	private val stores = Stores(app)
	private val storage = Storage(app.contentResolver)
	private val cache = FileCache<Uri>()
	private val walking = Mutex()

	val folders: StateFlow<List<Folder>> = stores.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

	private val filesFlow = MutableStateFlow<List<OrgFile>>(emptyList())
	val files: StateFlow<List<OrgFile>> = filesFlow

	// The message of the last walk that failed, e.g. on a folder whose permission was revoked.
	private val errorFlow = MutableStateFlow<String?>(null)
	val error: StateFlow<String?> = errorFlow

	init {
		viewModelScope.launch { stores.seed() }
	}

	// Walks every folder & re-reads each file whose size or time changed, under the stored settings.
	fun walk() {
		viewModelScope.launch(Dispatchers.IO) {
			walking.withLock {
				try {
					val settings = stores.settings.first()
					val found = LinkedHashMap<Uri, Found>()
					for (folder in stores.folders.first()) found.putAll(storage.orgFiles(folder))
					cache.refresh(found) { uri, f -> OrgFile.parse(f.path, storage.read(uri), settings) }
					filesFlow.value = cache.files
					errorFlow.value = null
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					errorFlow.value = e.message ?: e.toString()
				}
			}
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

	fun setRecursive(folder: Folder, recursive: Boolean) {
		viewModelScope.launch {
			stores.setRecursive(folder.uri, recursive)
			walk()
		}
	}
}
