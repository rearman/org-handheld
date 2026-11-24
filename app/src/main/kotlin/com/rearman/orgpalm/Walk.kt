package com.rearman.orgpalm

import com.rearman.orgpalm.org.OrgFile

// The size & last-modified time of a document, as its provider reports them; a change in either sends
// the file to be read again.
data class Stamp(val size: Long, val modified: Long)

// One child of a folder, as the document provider lists it.
data class Doc(val id: String, val name: String, val folder: Boolean, val stamp: Stamp)

// An org file that the walk found: its document id, its path below the picked folder, & its stamp.
data class Found(val id: String, val path: String, val stamp: Stamp)

// A picked folder: its tree URI & whether its subfolders are read too.
data class Folder(val uri: String, val recursive: Boolean)

object Walk {
	// The .org files under the folder [root]: its own, & w/ [recursive] those of its subfolders at any
	// depth. A name that begins w/ a dot is skipped, file or folder, as org-agenda-file-regexp skips a
	// dot-file. [children] lists a folder by its document id. The files come sorted by path, as
	// directory-files sorts a folder of org-agenda-files.
	fun orgFiles(root: String, recursive: Boolean, children: (String) -> List<Doc>): List<Found> {
		val out = ArrayList<Found>()
		fun visit(id: String, prefix: String) {
			for (d in children(id)) {
				when {
					d.name.startsWith(".") -> {}
					d.folder -> if (recursive) visit(d.id, prefix + d.name + "/")
					d.name.endsWith(".org") -> out.add(Found(d.id, prefix + d.name, d.stamp))
				}
			}
		}
		visit(root, "")
		return out.sortedBy { it.path }
	}
}

// The parsed files of the walk, one per document key (its URI), each w/ the stamp at which it was read.
class FileCache<K> {
	private class Held(val stamp: Stamp, val file: OrgFile)

	@Volatile
	private var held: Map<K, Held> = emptyMap()

	// The files w/ their keys, in the order of the last refresh.
	val files: List<Pair<K, OrgFile>> get() = held.map { (key, h) -> key to h.file }

	// Drops [key], so that the next refresh reads it again: after the app writes the file, whose size &
	// time may not change at the provider's resolution.
	fun forget(key: K) {
		held = held - key
	}

	// Drops every file, so that the next refresh reads all again: after a change of the settings.
	fun clear() {
		held = emptyMap()
	}

	// Brings the cache to [found]: reads a file that is new or whose size or time changed, keeps the
	// rest as they are, & drops a file that the walk no longer found.
	fun refresh(found: Map<K, Found>, read: (K, Found) -> OrgFile) {
		val old = held
		val next = LinkedHashMap<K, Held>()
		for ((key, f) in found) {
			val had = old[key]
			next[key] = if (had != null && had.stamp == f.stamp) had else Held(f.stamp, read(key, f))
		}
		held = next
	}
}
