package com.rearman.orgpalm.org

// One source line: its text w/o the terminator, and the terminator itself: "\n", "\r\n", or "" for a
// last line that has none.
data class Line(val text: String, val eol: String) {
	val raw: String get() = text + eol
}

// A heading of an OrgDocument. [start] is the index of the heading line, [end] the first line after its
// own section (the next heading of any level), and [subtreeEnd] the first line after its last
// descendant. The offsets are char offsets into OrgDocument.render(), the BOM included.
class Heading internal constructor(
	val level: Int,
	val start: Int,
	val end: Int,
	val subtreeEnd: Int,
	val startOffset: Int,
	val endOffset: Int,
	val subtreeEndOffset: Int,
) {
	var parent: Heading? = null
		private set
	private val childList = ArrayList<Heading>()
	val children: List<Heading> get() = childList

	internal fun attach(child: Heading) {
		child.parent = this
		childList.add(child)
	}
}

// A lossless tree of an Org file: the header lines before the first heading, then the headings, each
// w/ its exact span. render() gives back the input char for char.
class OrgDocument private constructor(val bom: Boolean, val lines: List<Line>) {
	// Every heading in document order.
	val headings: List<Heading>

	// The headings that have no parent.
	val roots: List<Heading>

	// The index of the first heading line, or lines.size when the file has no heading.
	val headerEnd: Int

	// The line ending that new lines take: the file's own (see splitLines), LF for a file w/o one.
	val lineEnding: String = lines.firstOrNull { it.eol.isNotEmpty() }?.eol ?: "\n"

	init {
		val offsets = IntArray(lines.size + 1)
		offsets[0] = if (bom) 1 else 0
		for (i in lines.indices) offsets[i + 1] = offsets[i] + lines[i].text.length + lines[i].eol.length
		val starts = lines.indices.filter { headingLevel(lines[it].text) > 0 }
		val all = ArrayList<Heading>(starts.size)
		val top = ArrayList<Heading>()
		val stack = ArrayDeque<Heading>()
		for ((k, s) in starts.withIndex()) {
			val level = headingLevel(lines[s].text)
			val end = if (k + 1 < starts.size) starts[k + 1] else lines.size
			var subtreeEnd = lines.size
			for (j in k + 1 until starts.size) {
				if (headingLevel(lines[starts[j]].text) <= level) {
					subtreeEnd = starts[j]
					break
				}
			}
			val h = Heading(level, s, end, subtreeEnd, offsets[s], offsets[end], offsets[subtreeEnd])
			while (stack.isNotEmpty() && stack.last().level >= level) stack.removeLast()
			val parent = stack.lastOrNull()
			if (parent != null) parent.attach(h) else top.add(h)
			stack.addLast(h)
			all.add(h)
		}
		headings = all
		roots = top
		headerEnd = starts.firstOrNull() ?: lines.size
	}

	fun render(): String = render(bom, lines)

	// The values of every "#+NAME:" line (case aside), in file order, each w/ the text after the colon
	// as written. Limit: a line inside a block counts, where Org's org-collect-keywords would skip it.
	fun keywordValues(name: String): List<String> {
		val re = Regex("""[ \t]*#\+""" + Regex.escape(name) + """:(.*)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
		return lines.mapNotNull { re.matchEntire(it.text)?.groupValues?.get(1) }
	}

	// The lines of a heading's own section, the heading line first.
	fun sectionLines(h: Heading): List<Line> = lines.subList(h.start, h.end)

	companion object {
		const val BOM = '\uFEFF'

		fun parse(text: String): OrgDocument {
			val bom = text.startsWith(BOM)
			return OrgDocument(bom, splitLines(if (bom) text.substring(1) else text))
		}

		fun render(bom: Boolean, lines: List<Line>): String = buildString {
			if (bom) append(BOM)
			for (line in lines) append(line.text).append(line.eol)
		}

		// Split text into lines as Emacs reads them: CRLF lines when every line ending in the file is
		// CRLF, else LF lines w/ any carriage return kept as text (Emacs shows it as ^M). An edit then
		// treats a stray CR as Org does.
		fun splitLines(text: String): List<Line> {
			val crlf = allCrlf(text)
			val out = ArrayList<Line>()
			var i = 0
			while (i < text.length) {
				val nl = text.indexOf('\n', i)
				if (nl < 0) {
					out.add(Line(text.substring(i), ""))
					break
				}
				out.add(if (crlf) Line(text.substring(i, nl - 1), "\r\n") else Line(text.substring(i, nl), "\n"))
				i = nl + 1
			}
			return out
		}

		private fun allCrlf(text: String): Boolean {
			var seen = false
			var nl = text.indexOf('\n')
			while (nl >= 0) {
				if (nl == 0 || text[nl - 1] != '\r') return false
				seen = true
				nl = text.indexOf('\n', nl + 1)
			}
			return seen
		}

		// The level of a heading line (stars followed by a space, as org-outline-regexp has it), or 0.
		fun headingLevel(text: String): Int {
			var n = 0
			while (n < text.length && text[n] == '*') n++
			return if (n > 0 && n < text.length && text[n] == ' ') n else 0
		}

		// A line that Org counts as empty: spaces and tabs alone.
		internal fun isBlank(text: String): Boolean = text.all { isBlankChar(it) }

		// The separators of Emacs's split-string by default.
		private val SPLIT = Regex("[ \\f\\t\\n\\r\\x0B]+")

		// The words of a keyword line's value, as split-string w/ its default separators gives them.
		internal fun words(text: String): List<String> = text.split(SPLIT).filter { it.isNotEmpty() }

		// A space or a tab: the blanks Org skips and trims ("[ \t]").
		internal fun isBlankChar(c: Char): Boolean = c == ' ' || c == '\t'

		// The last line in [from, to) that is not blank, or from - 1 when all are.
		internal fun lastNonBlank(lines: List<Line>, from: Int, to: Int): Int {
			var i = to - 1
			while (i >= from && isBlank(lines[i].text)) i--
			return i
		}

		// The index where the run of spaces and tabs that ends at [end] begins, no lower than [floor]:
		// Emacs's skip-chars-backward " \t".
		internal fun blankStart(text: String, end: Int, floor: Int = 0): Int {
			var i = end
			while (i > floor && isBlankChar(text[i - 1])) i--
			return i
		}
	}
}

// A property line of a drawer: its line index, key, and value.
data class PropertyLine(val index: Int, val key: String, val value: String)

// The layout of one heading's section, per DESIGN.md section 3: the heading line, an optional planning
// line right after it, an optional property drawer right after that, then the body, the section text
// after any further drawers (a LOGBOOK, say), as Org holds it. [stampEnd] ends the body's leading stamp
// lines, each of which holds one active timestamp and naught else (DESIGN.md section 3, Events); they
// stay part of the body. All indices point into the line list from which the section was read.
class Section private constructor(
	val start: Int,
	val end: Int,
	val planningLine: Int?,
	val drawerStart: Int?,
	val drawerEnd: Int?,
	val metaEnd: Int,
	val bodyStart: Int,
	val stampEnd: Int,
	val bodyEnd: Int,
) {
	// Every property line of the drawer.
	fun properties(lines: List<Line>): List<PropertyLine> {
		val from = drawerStart ?: return emptyList()
		val to = drawerEnd ?: return emptyList()
		return (from + 1 until to).mapNotNull { i ->
			PROPERTY_LINE.matchEntire(lines[i].text)?.let { PropertyLine(i, it.groupValues[1], it.groupValues[2]) }
		}
	}

	// The first property line w/ this key (Org compares keys w/o regard to case), or null.
	fun property(lines: List<Line>, key: String): PropertyLine? = properties(lines).firstOrNull { it.key.equals(key, ignoreCase = true) }

	// The planning line, parsed; Planning.NONE w/o one.
	fun planning(lines: List<Line>): Planning = planningLine?.let { Planning.parse(lines[it].text) } ?: Planning.NONE

	// The stamps of the leading stamp lines.
	fun stamps(lines: List<Line>): List<Timestamp> = (bodyStart until stampEnd).mapNotNull { stampOf(lines[it].text) }

	// True when the leading stamp lines make an event, whose form shows the rest of the body as text.
	fun isEvent(lines: List<Line>): Boolean = OrgFile.eventStamps(stamps(lines)) != null

	// The body text as written.
	fun body(lines: List<Line>): String = text(lines, bodyStart)

	// The body after the leading stamp lines.
	fun rest(lines: List<Line>): String = text(lines, stampEnd)

	private fun text(lines: List<Line>, from: Int): String = (from until bodyEnd).joinToString("\n") { lines[it].text }

	companion object {
		internal val PLANNING_LINE = Regex("^[ \\t]*(?:${PlanKey.ALTERNATION}):")
		private val PROPERTIES = Regex("[ \\t]*:PROPERTIES:[ \\t]*", RegexOption.IGNORE_CASE)
		internal val END = Regex("[ \\t]*:END:[ \\t]*", RegexOption.IGNORE_CASE)
		internal val DRAWER = Regex("[ \\t]*:[\\w-]+:[ \\t]*")
		private val PROPERTY_LINE = Regex("[ \\t]*:(\\S+?):(?:[ \\t]+(.*?))?[ \\t]*", RegexOption.DOT_MATCHES_ALL)

		// The stamp of a line that holds one active timestamp and naught else, or null.
		fun stampOf(text: String): Timestamp? = Timestamp.parse(text.trim { OrgDocument.isBlankChar(it) })?.takeIf { it.active }

		// Read the section of the heading at line [start].
		fun of(lines: List<Line>, start: Int): Section {
			var end = start + 1
			while (end < lines.size && OrgDocument.headingLevel(lines[end].text) == 0) end++
			var i = start + 1
			var planning: Int? = null
			if (i < end && PLANNING_LINE.containsMatchIn(lines[i].text)) {
				planning = i
				i++
			}
			var drawerStart: Int? = null
			var drawerEnd: Int? = null
			if (i < end && PROPERTIES.matches(lines[i].text)) {
				val close = closeOf(lines, i, end)
				if (close != null) {
					drawerStart = i
					drawerEnd = close
					i = close + 1
				}
			}
			val metaEnd = i
			val bodyStart = skipDrawers(lines, metaEnd, end)
			val bodyEnd = maxOf(bodyStart, OrgDocument.lastNonBlank(lines, bodyStart, end) + 1)
			var stampEnd = bodyStart
			while (stampEnd < bodyEnd && stampOf(lines[stampEnd].text) != null) stampEnd++
			return Section(start, end, planning, drawerStart, drawerEnd, metaEnd, bodyStart, stampEnd, bodyEnd)
		}

		// The :END: line that closes the drawer opened at [open], within the section.
		private fun closeOf(lines: List<Line>, open: Int, end: Int): Int? {
			for (j in open + 1 until end) if (END.matches(lines[j].text)) return j
			return null
		}

		private fun skipDrawers(lines: List<Line>, from: Int, end: Int): Int {
			var i = from
			while (i < end && DRAWER.matches(lines[i].text) && !END.matches(lines[i].text)) {
				val close = closeOf(lines, i, end) ?: break
				i = close + 1
			}
			return i
		}
	}
}
