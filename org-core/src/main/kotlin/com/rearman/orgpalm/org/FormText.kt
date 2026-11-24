package com.rearman.orgpalm.org

// Text from a form, written into the file as typed: Org reads it as it would read the same text typed
// in Emacs, structure and all.
object FormText {
	// Line breaks of text from a form.
	private val BREAKS = Regex("[\r\n]+")

	// Text from a form as one line: line breaks become spaces, and the ends are trimmed as
	// org-edit-headline trims them.
	fun oneLine(text: String): String = text.replace(BREAKS, " ").trim { OrgDocument.isBlankChar(it) }

	// The lines of body text from a form: CRLF and lone CR count as line breaks, and trailing blank
	// lines are dropped, since the parser does not count them as body.
	fun lines(text: String): List<String> =
		text.replace("\r\n", "\n").replace('\r', '\n').split('\n').dropLastWhile { OrgDocument.isBlank(it) }
}
