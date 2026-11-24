package com.rearman.orgpalm.org

// Where a heading stood when the app last read its file (DESIGN.md section 4 rule 3): the index of its
// heading line, the heading line, and the text of its section. Each save re-reads the file and finds
// the heading again w/ locate().
//
// [sectionText] is the heading's own section: the lines after the heading line up to its first child
// (or the next heading), joined by "\n" whatever their line endings. A change to a child or a sibling
// in Emacs then leaves the anchor good, while a change to the heading's own lines refuses the save.
//
// [copies] is the count of headings w/ the same heading line and section text when the app read the
// file. The search beyond the anchor's line index serves only a heading that had no copy, lest a save
// land on a twin of the heading that Emacs changed.
data class Anchor(val lineIndex: Int, val headingLine: String, val sectionText: String, val copies: Int = 1) {
	companion object {
		fun of(file: OrgFile, h: Heading): Anchor = of(file.document, h)

		fun of(doc: OrgDocument, h: Heading): Anchor {
			val line = doc.lines[h.start].text
			val text = sectionText(doc, h)
			// A scan of every heading per anchor: O(n^2) over a file whose entries all take anchors, cheap
			// while only twin heading lines build a section text. A map of heading line to count, built
			// once per OrgFile, is the upgrade if a large file reads slowly.
			val copies = doc.headings.count { doc.lines[it.start].text == line && sectionText(doc, it) == text }
			return Anchor(h.start, line, text, copies)
		}

		internal fun sectionText(doc: OrgDocument, h: Heading): String =
			doc.lines.subList(h.start + 1, h.end).joinToString("\n") { it.text }
	}
}

// The heading of [file] that [anchor] names: the one at the anchor's line index when its heading line
// and section text are unchanged; else, for a heading that had no copy at the read, the one heading
// anywhere w/ that heading line and section text. Null for a conflict: no such heading, two or more, or
// a heading that had copies and left its line.
fun locate(file: OrgFile, anchor: Anchor): Heading? {
	val doc = file.document
	val matches = { h: Heading -> doc.lines[h.start].text == anchor.headingLine && Anchor.sectionText(doc, h) == anchor.sectionText }
	val atIndex = doc.headings.firstOrNull { it.start == anchor.lineIndex }
	if (atIndex != null && matches(atIndex)) return atIndex
	if (anchor.copies != 1) return null
	return doc.headings.filter(matches).singleOrNull()
}
