package com.rearman.orgpalm

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException

// The picked folders as the Storage Access Framework serves them (DESIGN.md section 3, Folders).
class Storage(private val resolver: ContentResolver) {
	// The .org files of [folder], keyed by document URI.
	fun orgFiles(folder: Folder): Map<Uri, Found> {
		val tree = Uri.parse(folder.uri)
		val found = Walk.orgFiles(DocumentsContract.getTreeDocumentId(tree), folder.recursive) { children(tree, it) }
		return found.associateBy { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
	}

	// The whole text of a document, read as UTF-8; a BOM stays in the text, where OrgDocument finds it.
	fun read(uri: Uri): String =
		(resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use { it.readBytes().toString(Charsets.UTF_8) }

	// The whole text of a document written in one stream, the old bytes truncated ("wt"), as UTF-8
	// (DESIGN.md section 4 rule 6).
	fun write(uri: Uri, text: String) {
		(resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(uri.toString())).use { it.write(text.toByteArray(Charsets.UTF_8)) }
	}

	// A new, empty document named [name] in the folder [folder], or null when the provider refuses. The
	// generic type keeps the name as given, where a text type could gain a ".txt".
	fun create(folder: Folder, name: String): Uri? {
		val tree = Uri.parse(folder.uri)
		val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		return DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
	}

	private fun children(tree: Uri, id: String): List<Doc> {
		val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
		val out = ArrayList<Doc>()
		resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
			while (c.moveToNext()) {
				val stamp = Stamp(if (c.isNull(3)) -1 else c.getLong(3), if (c.isNull(4)) -1 else c.getLong(4))
				out.add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) == Document.MIME_TYPE_DIR, stamp))
			}
		}
		return out
	}

	private companion object {
		val COLUMNS = arrayOf(
			Document.COLUMN_DOCUMENT_ID,
			Document.COLUMN_DISPLAY_NAME,
			Document.COLUMN_MIME_TYPE,
			Document.COLUMN_SIZE,
			Document.COLUMN_LAST_MODIFIED,
		)
	}
}
