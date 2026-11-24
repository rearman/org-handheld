package com.rearman.orgpalm.org

// The escaping rules of DESIGN.md section 4 rule 6: text from a form never parses as structure that
// Org would read and the user did not mean. The writer puts Org's documented escape, a zero-width space,
// only where Org's parser would otherwise read structure, and the parser undoes nothing. Text that holds
// the escape already reads as no structure, so a second pass adds naught.
object Escape {
	const val ZWSP = '​'

	// A title that ends in a tag group, as org-tag-group-re reads one.
	private val TAGS_AT_END = Regex("""(?:.*[ \t])?:[${HeadlineRegex.TAG_CHARS}:]+:""", RegexOption.DOT_MATCHES_ALL)

	// Line breaks of text from a form.
	private val BREAKS = Regex("[\r\n]+")

	// A keyword line, as org-keyword-regexp reads one: "#+" and a word and a colon.
	private val KEYWORD_LINE = Regex("""[ \t]*#\+\S+?:.*""", RegexOption.DOT_MATCHES_ALL)
	private val PLANNING_LIKE = Regex("""[ \t]*(?:${PlanKey.ALTERNATION}):.*""", RegexOption.DOT_MATCHES_ALL)

	// A title that begins w/ a TODO keyword of the file, COMMENT, or a priority cookie takes a leading
	// zero-width space.
	private fun needsLeading(text: String, keywords: Collection<String>): Boolean {
		val word = text.takeWhile { !OrgDocument.isBlankChar(it) }
		return word in keywords || word == OrgFile.COMMENT || HeadlineRegex.COOKIE.matches(word)
	}

	// Text from a form as one line: line breaks become spaces, and the ends are trimmed as
	// org-edit-headline trims them.
	fun oneLine(text: String): String = text.replace(BREAKS, " ").trim { OrgDocument.isBlankChar(it) }

	// Escape a title for a heading line.
	fun title(text: String, keywords: Collection<String>): String {
		var t = oneLine(text)
		if (needsLeading(t, keywords)) t = "$ZWSP$t"
		if (TAGS_AT_END.matches(t)) t += ZWSP
		return t
	}

	// The lines of text from a form: CRLF and lone CR count as line breaks.
	private fun formLines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

	// Split body text from a form into lines, each that Org would read as structure w/ a leading
	// zero-width space: a heading (stars and a space at column 0), a keyword line, a drawer (a :NAME:
	// line w/ an :END: line after it in the body), and, when [planningFirst] (the body follows the
	// heading line directly, w/ no planning line nor drawer), a first line that reads as a planning
	// line. Trailing blank lines are dropped, since the parser does not count them as body.
	fun bodyLines(text: String, planningFirst: Boolean): List<String> {
		val lines = formLines(text).dropLastWhile { OrgDocument.isBlank(it) }
		return lines.mapIndexed { i, line ->
			val structure = OrgDocument.headingLevel(line) > 0 ||
				KEYWORD_LINE.matches(line) ||
				(Section.DRAWER.matches(line) && (i + 1 until lines.size).any { Section.END.matches(lines[it]) }) ||
				(planningFirst && i == 0 && PLANNING_LIKE.matches(line))
			if (structure) "$ZWSP$line" else line
		}
	}
}
