package com.rearman.orgpalm

import com.rearman.orgpalm.org.OrgFile
import com.rearman.orgpalm.org.OrgSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class WalkTest {
	private val stamp = Stamp(10, 100)

	// A Termux home w/ a git checkout: a dot-file, a dot-folder, a file that is no org file, & a
	// subfolder that holds one more.
	private val tree = mapOf(
		"org" to listOf(
			Doc("org/inbox.org", "inbox.org", false, stamp),
			Doc("org/.#inbox.org", ".#inbox.org", false, stamp),
			Doc("org/notes.txt", "notes.txt", false, stamp),
			Doc("org/.git", ".git", true, stamp),
			Doc("org/work", "work", true, stamp),
			Doc("org/a.org", "a.org", false, stamp),
		),
		"org/.git" to listOf(Doc("org/.git/x.org", "x.org", false, stamp)),
		"org/work" to listOf(Doc("org/work/plant.org", "plant.org", false, stamp)),
	)

	private fun paths(recursive: Boolean) = Walk.orgFiles("org", recursive) { tree.getValue(it) }.map { it.path }

	@Test
	fun flatFolderReadsItsOwnOrgFilesAndSkipsDotFiles() {
		assertEquals(listOf("a.org", "inbox.org"), paths(recursive = false))
	}

	@Test
	fun recursiveFolderAddsSubfoldersAndSkipsDotFolders() {
		assertEquals(listOf("a.org", "inbox.org", "work/plant.org"), paths(recursive = true))
	}

	// A file is read again when its size or its time changed, kept when neither did, & dropped when gone.
	@Test
	fun cacheRereadsOnlyChangedFilesAndDropsGoneOnes() {
		val cache = FileCache<String>()
		val reads = ArrayList<String>()
		val read = { key: String, f: Found -> reads.add(key); OrgFile.parse(f.path, "* $key\n", OrgSettings.STARTING) }
		cache.refresh(
			mapOf(
				"a" to Found("a", "a.org", Stamp(10, 100)),
				"b" to Found("b", "b.org", Stamp(10, 100)),
				"c" to Found("c", "c.org", Stamp(10, 100)),
				"d" to Found("d", "d.org", Stamp(10, 100)),
			),
			read,
		)
		reads.clear()
		cache.refresh(
			mapOf(
				"a" to Found("a", "a.org", Stamp(10, 100)),
				"b" to Found("b", "b.org", Stamp(11, 100)),
				"c" to Found("c", "c.org", Stamp(10, 101)),
			),
			read,
		)
		assertEquals(listOf("b", "c"), reads)
		assertEquals(listOf("a.org", "b.org", "c.org"), cache.files.map { it.path })
	}
}
