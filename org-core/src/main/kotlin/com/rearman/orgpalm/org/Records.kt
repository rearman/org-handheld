package com.rearman.orgpalm.org

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.SortedSet
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

// The event view of an entry (DESIGN.md section 3, Events): the stamps of the leading stamp lines of its
// body, their shared repeater, the weekdays they name, the alarm of :APPT_WARNTIME:, and [body], the
// body w/o those lines, which the form shows as text.
data class Event(
	val timestamps: List<Timestamp>,
	val repeater: Repeater?,
	val weekdays: Set<DayOfWeek>,
	val warnMinutes: Int?,
	val body: String,
)

// One heading of any level in any file, read per DESIGN.md section 3.
//
// [tags] are the heading's own tags. [inheritedTags] come from #+FILETAGS: and the ancestors, in that
// order, w/o duplicates, w/o the own tags, and w/o the tags excluded from inheritance; [allTags] is the
// list org-get-tags gives. [body] is the section text after the planning line and the drawers, as Org
// holds it. [event] is set when the leading stamp lines of the body make an event.
//
// For the agenda: [activeStamps] are the plain active timestamps of the title and the body, [ranges]
// the date ranges there, and [sexps] the diary sexps that the app models; any other sexp gives no
// date, as a sexp that Org cannot evaluate gives none. [inAgenda] is false under a COMMENT heading or
// one tagged :ARCHIVE:.
//
// A planning keyword whose stamp does not parse locks its field (DESIGN.md section 4 rule 4).
data class Entry(
	val file: String,
	val anchor: Anchor,
	val outlinePath: List<String>,
	val level: Int,
	val state: String?,
	val done: Boolean,
	val open: Boolean,
	val priority: String?,
	val title: String,
	val tags: List<String>,
	val inheritedTags: List<String>,
	val scheduled: Timestamp?,
	val deadline: Timestamp?,
	val closed: Timestamp?,
	val properties: Map<String, String>,
	val body: String,
	val category: String,
	val event: Event?,
	val activeStamps: List<Timestamp>,
	val ranges: List<ClosedRange<LocalDate>>,
	val sexps: List<DiarySexp>,
	val inAgenda: Boolean,
	val scheduledLocked: Boolean,
	val deadlineLocked: Boolean,
	val closedLocked: Boolean,
) {
	val allTags: List<String> get() = inheritedTags + OrgFile.keepLast(tags)

	// The value of a property; Org compares keys w/o regard to case.
	fun property(key: String): String? = properties.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

	// The dates from [from] to [to] on which Org's agenda shows the entry: its SCHEDULED and DEADLINE
	// stamps, and the active stamps, the ranges (every day of each), and the diary sexps of its own text.
	// None under a COMMENT or :ARCHIVE: heading.
	fun agendaDates(from: LocalDate, to: LocalDate): SortedSet<LocalDate> {
		val out = TreeSet<LocalDate>()
		if (!inAgenda) return out
		scheduled?.let { out.addAll(it.planningDates(from, to, scheduled = true)) }
		deadline?.let { out.addAll(it.planningDates(from, to, scheduled = false)) }
		for (ts in activeStamps) out.addAll(ts.occurrences(from, to))
		for (r in ranges) {
			var d = maxOf(r.start, from)
			val last = minOf(r.endInclusive, to)
			while (!d.isAfter(last)) {
				out.add(d)
				d = d.plusDays(1)
			}
		}
		for (s in sexps) out.addAll(s.dates(from, to))
		return out
	}

	// Every open TODO entry, dated or not, outside a COMMENT or :ARCHIVE: subtree.
	val inTodoList: Boolean get() = open && inAgenda
}

// One sequence of TODO keywords, as one #+TODO: line gives it: [names] in order, the [done] ones among
// them, [type] for a #+TYP_TODO: line, and the log marks of each keyword that has any.
data class TodoSequence(
	val names: List<String>,
	val done: List<String>,
	val type: Boolean = false,
	val logs: Map<String, KeywordLog> = emptyMap(),
) {
	val head: String get() = names.first()

	companion object {
		// A keyword split as org-extract-log-state-settings splits "WAIT(w@/!)": 1 the name, 2 the key,
		// 3 the mark on entry, 4 the mark on leaving.
		private val KEYWORD = Regex("""(.*?)(?:\(([^!@/])?([!@])?(?:/([!@]))?\))?""", RegexOption.DOT_MATCHES_ALL)

		// The fast-access key and marks of a keyword outside Org's pattern, dropped as
		// org-remove-keyword-keys drops them.
		private val KEYS = Regex("""\(.*\)$""")

		private fun mode(mark: String?): LogMode = when (mark) {
			"!" -> LogMode.TIME
			"@" -> LogMode.NOTE
			else -> LogMode.NONE
		}

		// A keyword w/o its key and marks: WAIT(w@/!) gives WAIT.
		private fun name(word: String): String = KEYWORD.matchEntire(word)?.groupValues?.get(1) ?: word.replace(KEYS, "")

		// One sequence from its words, as one #+TODO: line or one entry of org-todo-keywords gives it:
		// w/o a "|", its last keyword is the done one.
		fun of(words: List<String>, type: Boolean = false): TodoSequence {
			val names = ArrayList<String>()
			val logs = LinkedHashMap<String, KeywordLog>()
			for (w in words.filter { it != "|" }) {
				val m = KEYWORD.matchEntire(w)
				val kw = name(w)
				names.add(kw)
				if (m != null && (m.groups[3] != null || m.groups[4] != null)) {
					logs[kw] = KeywordLog(mode(m.groups[3]?.value), mode(m.groups[4]?.value))
				}
			}
			val bar = words.indexOf("|")
			val done = if (bar >= 0) words.drop(bar + 1).filter { it != "|" }.map { name(it) } else names.takeLast(1)
			return TodoSequence(names, done, type, logs)
		}

		// One sequence from the text of a #+TODO: line: "TODO(t) WAIT(w@/!) | DONE(d!)".
		fun parse(text: String, type: Boolean = false): TodoSequence = of(OrgDocument.words(text), type)
	}
}

// The TODO keywords of a file, as org-set-regexps-and-options reads them: the #+TYP_TODO: lines first,
// then the #+TODO: lines, then the #+SEQ_TODO: lines, each in file order; org-todo-keywords
// (OrgSettings) w/o any.
data class TodoKeywords(val sequences: List<TodoSequence>) {
	val all: List<String> = sequences.flatMap { it.names }
	val done: List<String> = sequences.flatMap { it.done }
	val open: List<String> = all.filter { it !in done }

	fun isDone(keyword: String?): Boolean = keyword != null && keyword in done

	fun isOpen(keyword: String?): Boolean = keyword != null && keyword in open

	// The first sequence that holds [keyword], as Org's org-todo-kwd-alist finds it.
	fun sequenceOf(keyword: String?): TodoSequence? = keyword?.let { k -> sequences.firstOrNull { k in it.names } }

	// The log marks of [keyword], as org-todo-log-states holds them: the last sequence that names it
	// w/ marks wins, since Org pushes each onto the front of the list.
	fun logOf(keyword: String?): KeywordLog? = keyword?.let { k -> sequences.lastOrNull { k in it.logs }?.logs?.get(k) }

	companion object {
		val DEFAULT = TodoKeywords(OrgSettings().todoKeywords)
		fun of(doc: OrgDocument, fallback: List<TodoSequence> = OrgSettings().todoKeywords): TodoKeywords {
			val words = { name: String -> doc.keywordValues(name).map { OrgDocument.words(it) }.filter { it.isNotEmpty() } }
			val sequences = words("TYP_TODO").map { TodoSequence.of(it, true) } +
				(words("TODO") + words("SEQ_TODO")).map { TodoSequence.of(it, false) }
			val kept = sequences.filter { it.names.isNotEmpty() }
			return TodoKeywords(kept.ifEmpty { fallback })
		}
	}
}

// The priority range of a file, as Org's values: a number 0 to 64, or the code of a letter A to Z. Its
// first #+PRIORITIES: line (highest, lowest, default) governs, which Org reads alone
// (org-collect-keywords takes the first value), else the range of the settings (org-priority-highest,
// org-priority-lowest, org-priority-default), which is A, C, B in stock Org.
data class Priorities(val highest: Int, val lowest: Int, val default: Int) {
	// The values from highest to lowest, as the cookies write them, for the form's pop-up list. Org's
	// org-priority-to-string refuses a value outside 0 to 64 and A to Z, so the list skips one.
	val values: List<String> get() = (highest..lowest).mapNotNull { text(it) }

	companion object {
		val DEFAULT = Priorities('A'.code, 'C'.code, 'B'.code)
		private val DIGITS = Regex("[0-9]+")

		// A cookie's text for a value, or null for none Org allows.
		fun text(value: Int): String? = when (value) {
			in 0..64 -> value.toString()
			in 'A'.code..'Z'.code -> value.toChar().toString()
			else -> null
		}

		// org-priority-to-value, per word: the first run of digits as a number, else the code of the
		// first character. A run too long for an Int gives null, and the file keeps the settings' range.
		private fun value(word: String): Int? {
			val digits = DIGITS.find(word) ?: return word[0].code
			return digits.value.toIntOrNull()
		}

		fun of(doc: OrgDocument, fallback: Priorities = DEFAULT): Priorities {
			val line = doc.keywordValues("PRIORITIES").firstOrNull() ?: return fallback
			val words = OrgDocument.words(line)
			if (words.size < 3) return fallback
			val h = value(words[0]) ?: return fallback
			val l = value(words[1]) ?: return fallback
			val d = value(words[2]) ?: return fallback
			return Priorities(h, l, d)
		}
	}
}

// A heading line split as org-complex-heading-regexp splits it. [title] is as written, as Org reads it.
data class Headline(
	val level: Int,
	val keyword: String?,
	val priority: String?,
	val title: String,
	val tags: List<String>,
) {
	companion object {
		fun parse(text: String, keywords: List<String>): Headline {
			val m = HeadlineRegex.complex(keywords).matchEntire(text) ?: error("Not a heading line: $text")
			val cookie = m.groups[3]?.value
			return Headline(
				m.groupValues[1].length,
				m.groups[2]?.value,
				cookie?.let { HeadlineRegex.cookieValue(it) },
				m.groups[4]?.value ?: "",
				HeadlineRegex.splitTags(m.groups[5]?.value),
			)
		}
	}
}

// The regexps of a heading line, after Org's.
internal object HeadlineRegex {
	// Org's tag characters, [:alnum:]_@#%. [:alnum:] in Emacs is a Unicode letter or digit; \p{L}\p{Nd}
	// is close and misses only the rarer digit categories.
	const val TAG_CHARS = """\p{L}\p{Nd}_@#%"""
	val TAG = Regex("[$TAG_CHARS]+")

	// A priority cookie, as org-priority-value-regexp allows its value.
	val COOKIE = Regex("""\[#(?:[A-Z]|[0-9]|[1-5][0-9]|6[0-4])\]""")

	// org-tag-line-re: group 1 is the tag group at the end of the line.
	val TAG_LINE = Regex("""\*+ (?:.*[ \t])?(:[$TAG_CHARS:]+:)[ \t]*""", RegexOption.DOT_MATCHES_ALL)

	private val cache = ConcurrentHashMap<List<String>, Regex>()

	// The keywords as one alternation, longest first; one that matches naught when there are none.
	fun keywordAlternation(keywords: List<String>): String =
		if (keywords.isEmpty()) "(?!)" else keywords.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

	// org-complex-heading-regexp: 1 stars, 2 keyword, 3 priority cookie, 4 title, 5 tags.
	fun complex(keywords: List<String>): Regex = cache.getOrPut(keywords) {
		Regex(
			"""(\*+)(?: +(${keywordAlternation(keywords)}))?(?: +(${COOKIE.pattern}))?(?: +(.*?))??(?:[ \t]+(:[$TAG_CHARS:]+:))?[ \t]*""",
			RegexOption.DOT_MATCHES_ALL,
		)
	}

	// The value of a cookie such as "[#A]".
	fun cookieValue(cookie: String): String = cookie.substring(2, cookie.length - 1)

	// The tags of a tag group such as ":work:plc:".
	fun splitTags(group: String?): List<String> = group?.split(':')?.filter { it.isNotEmpty() } ?: emptyList()
}

// The planning keywords, each w/ the regexp that finds it and its timestamp (group 1, w/o brackets),
// after org-closed-time-regexp, org-deadline-time-regexp, and org-scheduled-time-regexp.
internal enum class PlanKey(val word: String, val regex: Regex, private val open: String, private val close: String) {
	CLOSED("CLOSED:", Regex("""\bCLOSED: *\[([^\]]+)\]"""), "[", "]"),
	DEADLINE("DEADLINE:", Regex("""\bDEADLINE: *<([^>]+)>"""), "<", ">"),
	SCHEDULED("SCHEDULED:", Regex("""\bSCHEDULED: *<([^>]+)>"""), "<", ">");

	fun stamp(m: MatchResult): Timestamp? = Timestamp.parse(open + m.groupValues[1] + close)

	// The pattern of org-remove-timestamp-with-keyword, which takes an active stamp w/ the blanks after
	// it and wants at least one space after the keyword. [regex] (org-deadline-time-regexp and its kin)
	// allows none and leaves the blanks, so Org keeps two patterns and so does this.
	val removal: Regex = Regex("\\b" + Regex.escape(word) + " +<[^>\\n]+>[ \\t]*")

	companion object {
		// The three keywords w/o their colons, as a regexp alternation.
		val ALTERNATION: String = entries.joinToString("|") { it.name }

		// org-keyword-time-not-clock-regexp: any planning keyword w/ its timestamp.
		val ANY = Regex("""\b(?:$ALTERNATION): *[\[<][^\]>]+[\]>]""")
	}
}

// The planning line of a heading. A keyword whose timestamp does not parse reads as null and sets its
// lock (DESIGN.md section 3): the form shows that field read-only.
data class Planning(
	val closed: Timestamp?,
	val deadline: Timestamp?,
	val scheduled: Timestamp?,
	val closedLocked: Boolean = false,
	val deadlineLocked: Boolean = false,
	val scheduledLocked: Boolean = false,
) {
	companion object {
		val NONE = Planning(null, null, null)

		fun parse(text: String): Planning {
			val found = PlanKey.entries.associateWith { key -> key.regex.find(text) }
			val stamps = found.mapValues { (key, m) -> m?.let { key.stamp(it) } }
			val locked = { key: PlanKey -> found[key] != null && stamps[key] == null }
			return Planning(
				stamps[PlanKey.CLOSED], stamps[PlanKey.DEADLINE], stamps[PlanKey.SCHEDULED],
				locked(PlanKey.CLOSED), locked(PlanKey.DEADLINE), locked(PlanKey.SCHEDULED),
			)
		}
	}
}

// A parsed org file of any layout: its [path] (which gives the category of last resort), its document,
// and an Entry per heading, read per DESIGN.md section 3 under [settings] w/ the file's own #+STARTUP:
// words applied.
class OrgFile(val path: String, val document: OrgDocument, settings: OrgSettings = OrgSettings()) {
	val settings: OrgSettings = settings.forFile(document)
	val keywords: TodoKeywords = TodoKeywords.of(document, this.settings.todoKeywords)
	val priorities: Priorities = Priorities.of(document, this.settings.priorities)

	// The tags of every #+FILETAGS: line, in file order, as org-set-regexps-and-options collects them.
	val fileTags: List<String> =
		document.keywordValues("FILETAGS").flatMap { value -> OrgDocument.words(value).flatMap { HeadlineRegex.splitTags(it) } }

	// The value of the last #+CATEGORY: line, else the file name w/o ".org", as
	// org-refresh-category-properties finds it. Limit: a CATEGORY property in a drawer above the first
	// heading or a #+PROPERTY: line is not read.
	val fileCategory: String =
		document.keywordValues("CATEGORY").map { v -> v.trim { OrgDocument.isBlankChar(it) } }.lastOrNull { it.isNotEmpty() }
			?: path.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".org")

	fun headline(h: Heading): Headline = Headline.parse(document.lines[h.start].text, keywords.all)

	fun section(h: Heading): Section = Section.of(document.lines, h.start)

	// Every heading's entry, in document order.
	val entries: List<Entry> by lazy { document.headings.map { entry(it) } }

	// The :CATEGORY: of the heading or its nearest ancestor that has one, else the file's category, as
	// org-get-category gives it. An empty value counts as none.
	fun category(h: Heading): String {
		var at: Heading? = h
		while (at != null) {
			section(at).property(document.lines, FieldChange.CATEGORY)?.value?.takeIf { it.isNotEmpty() }?.let { return it }
			at = at.parent
		}
		return fileCategory
	}

	// The ancestors of a heading, outermost first.
	private fun ancestors(h: Heading): List<Heading> = generateSequence(h.parent) { it.parent }.toList().asReversed()

	// A heading the agenda skips w/ its subtree: one whose title begins w/ COMMENT, or one tagged :ARCHIVE:
	// (org-agenda-skip-comment-trees and org-agenda-skip-archived-trees).
	private fun skipped(h: Heading): Boolean {
		val head = headline(h)
		return head.title == COMMENT || head.title.startsWith("$COMMENT ") || ARCHIVE in head.tags
	}

	fun entry(h: Heading): Entry {
		val lines = document.lines
		val s = section(h)
		val head = headline(h)
		val planning = s.planning(lines)
		val up = ancestors(h)
		val stamps = eventStamps(s.stamps(lines))
		val properties = LinkedHashMap<String, String>()
		for (p in s.properties(lines)) properties.putIfAbsent(p.key, p.value)
		val warn = s.property(lines, FieldChange.WARN_TIME)?.value?.trim { OrgDocument.isBlankChar(it) }?.toIntOrNull()
		val own = head.tags
		val inherited = keepLast(fileTags + up.flatMap { headline(it).tags })
			.filter { it !in own && it !in settings.tagsExcludedFromInheritance }
		val dates = AgendaText.scan(listOf(head.title) + AgendaText.ownLines(lines, s))
		return Entry(
			file = path,
			anchor = Anchor.of(document, h),
			outlinePath = up.map { headline(it).title },
			level = h.level,
			state = head.keyword,
			done = keywords.isDone(head.keyword),
			open = keywords.isOpen(head.keyword),
			priority = head.priority,
			title = head.title,
			tags = own,
			inheritedTags = inherited,
			scheduled = planning.scheduled,
			deadline = planning.deadline,
			closed = planning.closed,
			properties = properties,
			body = s.body(lines),
			category = category(h),
			event = stamps?.let { Event(it, it.first().repeater, it.map { ts -> ts.date.dayOfWeek }.toSortedSet(), warn, s.rest(lines)) },
			activeStamps = dates.stamps,
			ranges = dates.ranges,
			sexps = dates.sexps,
			inAgenda = (up + h).none { skipped(it) },
			scheduledLocked = planning.scheduledLocked,
			deadlineLocked = planning.deadlineLocked,
			closedLocked = planning.closedLocked,
		)
	}

	// The heading line w/o its stars, as org-get-heading gives it: keyword, cookie, title, and tags.
	private fun headingText(h: Heading): String =
		document.lines[h.start].text.substring(h.level + 1).trimEnd { OrgDocument.isBlankChar(it) }

	// Why a change of [h] from [from] to the done state [to] is refused, as Org's org-blocker-hook
	// refuses it, or null: an open TODO among its descendants, an open TODO ahead of it under a parent
	// (or an open TODO ancestor's parent) w/ an :ORDERED: property (org-enforce-todo-dependencies), or
	// an unchecked box in its own section (org-enforce-todo-checkbox-dependencies). The reason quotes
	// the blocking heading as Org's message does. Limit: a box inside a block counts.
	fun blocker(h: Heading, from: String?, to: String?): String? {
		if (to == null || !keywords.isDone(to) || keywords.isDone(from)) return null
		if (settings.enforceTodoDependencies) {
			val open = { x: Heading -> keywords.isOpen(headline(x).keyword) }
			document.headings.firstOrNull { it.start > h.start && it.start < h.subtreeEnd && open(it) }
				?.let { return "\"${headingText(it)}\"" }
			var cur = h
			var parent = h.parent
			while (parent != null) {
				val p = parent
				val ordered = section(p).property(document.lines, ORDERED)?.value?.let { it.isNotEmpty() && it != "nil" } == true
				if (ordered) {
					document.headings.firstOrNull { it.start > p.start && it.start < cur.start && open(it) }
						?.let { return "\"${headingText(it)}\"" }
				}
				if (!open(p)) break
				cur = p
				parent = p.parent
			}
		}
		if (settings.enforceCheckboxDependencies && (h.start + 1 until h.end).any { CHECKBOX.matches(document.lines[it].text) }) {
			return "contained checkboxes"
		}
		return null
	}

	companion object {
		const val COMMENT = "COMMENT"
		const val ARCHIVE = "ARCHIVE"
		private const val ORDERED = "ORDERED"

		// An unchecked or partly checked box on a list item, as org-block-todo-from-checkboxes finds it.
		private val CHECKBOX = Regex("""[ \t]*(?:[-+*]|\d+[.)])[ \t]+(?:\[@(?:start:)?(?:\d+|[A-Za-z])\][ \t]*)?\[[- ]\].*""", RegexOption.DOT_MATCHES_ALL)

		fun parse(path: String, text: String, settings: OrgSettings = OrgSettings()): OrgFile =
			OrgFile(path, OrgDocument.parse(text), settings)

		// [tags] w/o duplicates, each at its last place, as Org's nreverse, delete-dups, nreverse keeps
		// the most local copy of a tag.
		internal fun keepLast(tags: List<String>): List<String> = tags.asReversed().distinct().asReversed()

		// The stamps of an entry's leading stamp lines when they make one event (DESIGN.md section 3,
		// Events), or null for none or another shape: a habit, an hourly repeat, or several stamps that
		// are no weekday set.
		fun eventStamps(stamps: List<Timestamp>): List<Timestamp>? {
			if (stamps.isEmpty()) return null
			if (stamps.any { it.repeater?.unit == RepeatUnit.HOUR || it.repeater?.habit != null }) return null
			return if (isWeekdaySet(stamps)) stamps else null
		}

		// Several timestamps make one event when they repeat weekly (+Nw, .+Nw, or ++Nw), share the
		// time, the repeater, and the warning, and their dates rise within the six days after the first
		// (DESIGN.md section 3). One timestamp is always a set of its own day.
		fun isWeekdaySet(stamps: List<Timestamp>): Boolean {
			val first = stamps.first()
			if (stamps.size > 1 && first.repeater?.unit != RepeatUnit.WEEK) return false
			val last = first.date.plusDays(6)
			for (i in 1 until stamps.size) {
				val t = stamps[i]
				if (t.start != first.start || t.end != first.end || t.repeater != first.repeater) return false
				if (t.warning != first.warning || t.warningFirst != first.warningFirst || t.active != first.active) return false
				if (!t.date.isAfter(stamps[i - 1].date) || t.date.isAfter(last)) return false
			}
			return true
		}

		// The timestamps of a weekly event on [days]: [first] on its own date, and a copy w/ the same
		// time and repeater on each other day of [days] within the six days after it.
		fun weekdayTimestamps(first: Timestamp, days: Set<DayOfWeek>): List<Timestamp> =
			(0L..6L).map { first.date.plusDays(it) }
				.filter { it == first.date || it.dayOfWeek in days }
				.map { first.copy(date = it) }
	}
}

// The dates in an entry's own text that Org's agenda reads: plain active stamps, date ranges, and diary
// sexps, each found once (a range's ends and a sexp are not read again as plain stamps).
internal class AgendaText(
	val stamps: List<Timestamp>,
	val ranges: List<ClosedRange<LocalDate>>,
	val sexps: List<DiarySexp>,
) {
	companion object {
		private val RANGE = Regex("""(<\d{4}-\d{2}-\d{2}[^<>\n]*>)--(<\d{4}-\d{2}-\d{2}[^<>\n]*>)""")

		// A block whose contents hold no timestamp objects for Org's parser.
		private val VERBATIM_BEGIN = Regex("""[ \t]*#\+BEGIN_(SRC|EXAMPLE|EXPORT|COMMENT)\b.*""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

		// A comment line ("# " or a lone "#"), a fixed-width line (": " or a lone ":"), or a keyword line.
		private val NO_OBJECTS = Regex("""[ \t]*(?:#(?: .*)?|:(?: .*)?|#\+.*)""", RegexOption.DOT_MATCHES_ALL)

		// The lines of a heading's section in which Org's agenda reads a timestamp: all but the planning
		// line (read as SCHEDULED and DEADLINE), comment, fixed-width, and keyword lines, and the lines of
		// src, example, export, and comment blocks, where org-at-timestamp-p 'agenda finds no timestamp
		// object. The drawers count, the property drawer too. Limit: inline verbatim and code (=...=,
		// ~...~) still count.
		fun ownLines(lines: List<Line>, s: Section): List<String> {
			val out = ArrayList<String>()
			var end: Regex? = null
			for (i in s.start + 1 until s.end) {
				val text = lines[i].text
				val close = end
				if (close != null) {
					if (close.matches(text)) end = null
					continue
				}
				val open = VERBATIM_BEGIN.matchEntire(text)
				if (open != null) {
					end = Regex("""[ \t]*#\+END_""" + open.groupValues[1] + """\b.*""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
					continue
				}
				if (i != s.planningLine && !NO_OBJECTS.matches(text)) out.add(text)
			}
			return out
		}

		fun scan(texts: List<String>): AgendaText {
			val stamps = ArrayList<Timestamp>()
			val ranges = ArrayList<ClosedRange<LocalDate>>()
			val sexps = ArrayList<DiarySexp>()
			for (text in texts) {
				var rest = text
				for (m in RANGE.findAll(text)) {
					val a = Timestamp.parse(m.groupValues[1])
					val b = Timestamp.parse(m.groupValues[2])
					if (a != null && b != null && a.active && b.active && !b.date.isBefore(a.date)) ranges.add(a.date..b.date)
				}
				rest = rest.replace(RANGE, " ")
				for (m in DiarySexp.STAMP.findAll(rest)) {
					DiarySexp.parse(m.groupValues[1])?.let { sexps.add(it) }
				}
				rest = rest.replace(DiarySexp.STAMP, " ")
				for (m in Timestamp.ACTIVE.findAll(rest)) Timestamp.parse(m.value)?.takeIf { it.active }?.let { stamps.add(it) }
			}
			return AgendaText(stamps, ranges, sexps)
		}
	}
}
