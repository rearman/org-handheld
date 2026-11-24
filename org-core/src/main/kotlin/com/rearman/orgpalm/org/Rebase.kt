package com.rearman.orgpalm.org

import java.security.MessageDigest
import java.util.Locale

// The hash check of DESIGN.md section 4 item 3, w/o the storage: the app keeps the hash of the bytes it
// last read; when the file's bytes differ ere a write, it reparses the fresh text and reapplies the
// edit, which finds its record by :ID:. The Storage Access Framework half comes in phase 4.
object Rebase {
	// The SHA-256 of a file's bytes, in hex.
	fun hash(bytes: ByteArray): String =
		MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

	// Reapply [edit] to [freshText]. Gives WriteResult.RecordDeleted when the record (or the parent of
	// an Append) is gone, and the app then reports the loss and keeps the edit in its undo buffer;
	// WriteResult.ReadOnly when Emacs made the record unreadable; and WriteResult.NotARecord when a
	// moved heading is no longer a task or note. A field whose fresh value already equals the edit's is
	// left as Emacs wrote it.
	fun rebase(edit: Edit, freshText: String, kind: FileKind, writer: OrgWriter): WriteResult =
		writer.apply(freshText, kind, edit)
}
