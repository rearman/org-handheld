package com.rearman.orgpalm.org

// The escaping rules of DESIGN.md section 4 item 6: text from a form never parses as structure. The
// writer escapes titles and body lines; the parser undoes the title and body escapes. Each undo is the
// exact inverse of its escape, so text that already looks escaped gets one more mark and reads back as
// typed.
object Escape {
	const val ZWSP = '\u200B'
	const val UNTITLED = "(untitled)"

	// A title that ends in a tag group, as org-tag-group-re reads one, w/ any zero-width spaces after it.
	private val TAGS_AT_END = Regex("""(?:.*[ \t])?:[${HeadlineRegex.TAG_CHARS}:]+:$ZWSP*""", RegexOption.DOT_MATCHES_ALL)

	// Line breaks of text from a form.
	private val BREAKS = Regex("[\r\n]+")

	// A body line split into its indentation, its leading commas, and the rest.
	private val BODY_LINE = Regex("""([ \t]*)(,*)(.*)""", RegexOption.DOT_MATCHES_ALL)
	private val HEADING_LIKE = Regex("""\*+ .*""", RegexOption.DOT_MATCHES_ALL)
	private val PLANNING_LIKE = Regex("""(?:${PlanKey.ALTERNATION}):.*""", RegexOption.DOT_MATCHES_ALL)
	private val SETTING_LIKE = Regex("""#\+\w+:.*""", RegexOption.DOT_MATCHES_ALL)

	// A title that begins w/ a TODO keyword of the file, COMMENT, or a priority cookie takes a leading
	// zero-width space.
	private fun needsLeading(text: String, keywords: Collection<String>): Boolean {
		val word = text.trimStart(ZWSP).takeWhile { !OrgDocument.isBlankChar(it) }
		return word in keywords || word == "COMMENT" || HeadlineRegex.COOKIE.matches(word)
	}

	// A title that ends in :word:, which Org would read as tags, takes a trailing zero-width space.
	private fun needsTrailing(text: String): Boolean = TAGS_AT_END.matches(text)

	// Text from a form as one line: line breaks become spaces, and the ends are trimmed as
	// org-edit-headline trims them.
	fun oneLine(text: String): String = text.replace(BREAKS, " ").trim { OrgDocument.isBlankChar(it) }

	// Escape a title (or the first line of a memo) for a heading line.
	fun title(text: String, keywords: Collection<String>): String {
		var t = oneLine(text)
		if (needsLeading(t, keywords)) t = "$ZWSP$t"
		if (needsTrailing(t)) t += ZWSP
		return t
	}

	// Undo title(): drop the zero-width space that each rule added.
	fun unTitle(text: String, keywords: Collection<String>): String {
		var t = text
		if (t.endsWith(ZWSP) && needsTrailing(t.dropLast(1))) t = t.dropLast(1)
		if (t.startsWith(ZWSP) && needsLeading(t.drop(1), keywords)) t = t.drop(1)
		return t
	}

	// A body line that would read as a heading, a drawer line, a planning line, or an in-buffer setting
	// (#+TODO: changes the keywords of the whole file) takes a comma. A heading needs its stars at
	// column 0. In an event body (timestamps), so does a line that begins w/ a timestamp, which the
	// parser would read as a timestamp line; DESIGN.md keeps such a line as typed in other bodies.
	private fun structural(indent: String, rest: String, timestamps: Boolean): Boolean =
		(indent.isEmpty() && HEADING_LIKE.matches(rest)) ||
			Section.DRAWER.matches(rest) ||
			PLANNING_LIKE.matches(rest) ||
			SETTING_LIKE.matches(rest) ||
			(timestamps && Timestamp.LINE_START.matches(rest))

	// Escape one body line w/ a leading comma after its indentation, as org-escape-code-in-region does.
	fun bodyLine(line: String, timestamps: Boolean = false): String {
		val (indent, commas, rest) = BODY_LINE.matchEntire(line)!!.destructured
		return if (structural(indent, rest, timestamps)) "$indent,$commas$rest" else line
	}

	// Undo bodyLine(): strip one comma from a line that would read as structure w/o it.
	fun unBodyLine(line: String, timestamps: Boolean = false): String {
		val (indent, commas, rest) = BODY_LINE.matchEntire(line)!!.destructured
		return if (commas.isNotEmpty() && structural(indent, rest, timestamps)) indent + commas.drop(1) + rest else line
	}

	// The lines of text from a form: CRLF and lone CR count as line breaks.
	private fun formLines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

	// Split body text from a form into escaped lines. In a task or a note, a line that begins w/ a
	// timestamp stays as typed. Trailing blank lines are dropped, since the parser does not count them
	// as body.
	fun bodyLines(text: String, timestamps: Boolean = false): List<String> =
		formLines(text).dropLastWhile { OrgDocument.isBlank(it) }.map { bodyLine(it, timestamps) }

	// Split memo text into a title and a body. A memo whose first line is blank takes the title
	// (untitled), and its whole text goes into the body.
	fun memo(text: String): Pair<String, String> {
		val lines = formLines(text)
		return if (OrgDocument.isBlank(lines[0])) UNTITLED to lines.joinToString("\n")
		else lines[0] to lines.drop(1).joinToString("\n")
	}
}
