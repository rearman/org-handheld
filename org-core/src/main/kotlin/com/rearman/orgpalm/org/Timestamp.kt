package com.rearman.orgpalm.org

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class RepeatUnit(val code: Char) {
	HOUR('h'),
	DAY('d'),
	WEEK('w'),
	MONTH('m'),
	YEAR('y');

	companion object {
		fun of(code: Char): RepeatUnit? = entries.firstOrNull { it.code == code }
	}
}

// An Org repeater: the mark ("+", ".+", or "++") kept as written, the count, the unit, and a habit's
// maximum interval ("/3d" in ".+1d/3d"), kept as written.
data class Repeater(val mark: String, val value: Int, val unit: RepeatUnit, val habit: String? = null) {
	init {
		require(mark == "+" || mark == ".+" || mark == "++") { "Bad repeater mark: $mark" }
		require(value >= 0) { "Negative repeater: $value" }
	}

	override fun toString(): String = "$mark$value${unit.code}${habit.orEmpty()}"

	companion object {
		private val RE = Regex("""(\.\+|\+\+|\+)(\d+)([hdwmy])(/\d+[hdwmy])?""")

		fun parse(text: String): Repeater? {
			val m = RE.matchEntire(text) ?: return null
			val value = m.groupValues[2].toIntOrNull() ?: return null
			val unit = RepeatUnit.of(m.groupValues[3][0]) ?: return null
			return Repeater(m.groupValues[1], value, unit, m.groups[4]?.value)
		}
	}
}

// How a line after the property drawer of calendar.org reads: a timestamp the app models, or a shape it
// shows read-only (a diary sexp, a date range, two timestamps, a timestamp w/ text after it, an inactive
// or a malformed timestamp).
enum class LineShape { READABLE, UNREADABLE }

// One line of the timestamp block: its shape and its stamp, null when the text does not parse.
data class TimestampLine(val shape: LineShape, val stamp: Timestamp?)

// An Org timestamp: <2026-11-23 Mon 09:00-10:00 +2w -1d> when active, [...] when inactive. The day name
// is not kept; format() writes the English one, as Org does under the C locale. [warning] is the
// warning or delay cookie ("-2d" or "--2d") as written, and [warningFirst] records that it came ahead
// of the repeater, since Org's agenda repeats a plain timestamp only when the repeater ends it.
data class Timestamp(
	val date: LocalDate,
	val start: LocalTime? = null,
	val end: LocalTime? = null,
	val repeater: Repeater? = null,
	val warning: String? = null,
	val active: Boolean = true,
	val warningFirst: Boolean = false,
) {
	init {
		require(end == null || start != null) { "An end time needs a start time" }
		require(warning == null || WARNING.matches(warning)) { "Bad warning: $warning" }
	}

	fun format(): String = buildString {
		append(if (active) '<' else '[')
		append(date.toString())
		append(' ').append(DAY_NAMES[date.dayOfWeek.value - 1])
		if (start != null) {
			append(' ').append(hhmm(start))
			if (end != null) append('-').append(hhmm(end))
		}
		val tail = listOfNotNull(repeater?.toString(), warning)
		for (part in if (warningFirst) tail.asReversed() else tail) append(' ').append(part)
		append(if (active) '>' else ']')
	}

	// The dates from [from] to [to] (inclusive) on which Org's agenda shows this as a plain timestamp
	// (one in a heading's text, as an event's are). The agenda shows an active timestamp on its date and,
	// when the timestamp ends in a repeater w/ a count above zero, on every repeat; a warning after the
	// repeater keeps it from repeating. repeats.txt checks this against Emacs.
	fun occurrences(from: LocalDate, to: LocalDate): List<LocalDate> {
		if (!active || to.isBefore(from)) return emptyList()
		val r = repeater
		if (r == null || r.value == 0 || (warning != null && !warningFirst)) {
			return if (inWindow(date, from, to)) listOf(date) else emptyList()
		}
		return repeatDates(r, from, to)
	}

	// The dates from [from] to [to] on which Org's agenda shows this timestamp on a SCHEDULED or a
	// DEADLINE line, for dates after today. Here the repeater counts in either order w/ the warning. A
	// delay on a SCHEDULED line ("-2d") hides the first date. Limits: the display on today (overdue
	// items, a deadline's warning period, the end of a delay) is not modeled; org-deadline-warning-days
	// counts as 0.
	fun planningDates(from: LocalDate, to: LocalDate, scheduled: Boolean): List<LocalDate> {
		if (!active || to.isBefore(from)) return emptyList()
		val r = repeater
		val dates = if (r == null || r.value == 0) {
			if (inWindow(date, from, to)) listOf(date) else emptyList()
		} else {
			repeatDates(r, from, to)
		}
		return if (scheduled && warning != null) dates.filter { it != date } else dates
	}

	// The repeats in [from, to]. A month or year repeat adds whole months to the first of the start
	// month and then the start day less one, so a day past the month's end runs into the next month
	// (Jan 31 +1m gives Mar 3 in a common year): the arithmetic of org-closest-date, which
	// calendar-absolute-from-gregorian lets overflow.
	private fun repeatDates(r: Repeater, from: LocalDate, to: LocalDate): List<LocalDate> {
		val out = ArrayList<LocalDate>()
		when (r.unit) {
			RepeatUnit.DAY, RepeatUnit.WEEK -> {
				val step = r.value.toLong() * (if (r.unit == RepeatUnit.WEEK) 7 else 1)
				val first = if (from.isAfter(date)) Math.floorDiv(ChronoUnit.DAYS.between(date, from) + step - 1, step) else 0L
				var d = date.plusDays(first * step)
				while (!d.isAfter(to)) {
					out.add(d)
					d = d.plusDays(step)
				}
			}
			RepeatUnit.MONTH, RepeatUnit.YEAR -> {
				val step = r.value.toLong() * (if (r.unit == RepeatUnit.YEAR) 12 else 1)
				// Start at a repeat whose month is at least one before the month of [from]; an earlier
				// one cannot reach [from], since the overflow is under a month.
				var k = 0L
				if (from.isAfter(date)) k = maxOf(0L, (ChronoUnit.MONTHS.between(date.withDayOfMonth(1), from.withDayOfMonth(1)) - 1) / step)
				while (true) {
					val d = addMonths(date, k * step)
					if (d.isAfter(to)) break
					if (!d.isBefore(from)) out.add(d)
					k++
				}
			}
			RepeatUnit.HOUR -> {
				// The dates of start + k * N hours. Org's agenda also folds in org-extend-today-until
				// here (0 by default); repeats.txt checks two hourly rows.
				val step = r.value.toLong()
				val t0 = moment
				val fromT = from.atStartOfDay()
				var k = if (fromT.isAfter(t0)) ChronoUnit.HOURS.between(t0, fromT) / step else 0L
				while (true) {
					val d = t0.plusHours(k * step).toLocalDate()
					if (d.isAfter(to)) break
					if (!d.isBefore(from) && out.lastOrNull() != d) out.add(d)
					k++
				}
			}
		}
		return out
	}

	// This timestamp moved by [n] units, as org-timestamp-change moves it: days and weeks by days,
	// months and years w/ the overflow of addMonths, hours w/ the time (a time range keeps its length).
	fun shifted(n: Long, unit: RepeatUnit): Timestamp = when (unit) {
		RepeatUnit.DAY -> copy(date = date.plusDays(n))
		RepeatUnit.WEEK -> copy(date = date.plusDays(7 * n))
		RepeatUnit.MONTH -> copy(date = addMonths(date, n))
		RepeatUnit.YEAR -> copy(date = addMonths(date, 12 * n))
		RepeatUnit.HOUR -> at(moment.plusHours(n))
	}

	// The date and start time; midnight w/o a time.
	val moment: LocalDateTime get() = date.atTime(start ?: LocalTime.MIDNIGHT)

	// This timestamp moved to [time], its time range keeping its length.
	fun at(time: LocalDateTime): Timestamp {
		val s = start ?: return copy(date = time.toLocalDate())
		val length = end?.let { ChronoUnit.MINUTES.between(s, it) }
		val t = time.toLocalTime().withSecond(0).withNano(0)
		return copy(date = time.toLocalDate(), start = t, end = length?.let { t.plusMinutes(it) })
	}

	companion object {
		private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
		private val SHAPE = Regex("""([<\[])(\d{4})-(\d{2})-(\d{2})([^<>\[\]\n]*?)[ \t]*([>\]])""")
		private val TIME = Regex("""(\d{1,2}):(\d{2})(?:-(\d{1,2}):(\d{2}))?""")
		private val WARNING = Regex("""--?\d+[hdwmy]""")
		private val DAY_NAME = Regex("""\p{L}+\.?""")

		// Text that begins w/ a timestamp, active or inactive, or a diary sexp: in calendar.org a line
		// that begins so is a timestamp line.
		internal val LINE_START = Regex("""(?:[<\[]\d{4}-\d{2}-\d{2}|<%%\().*""", RegexOption.DOT_MATCHES_ALL)

		// An active timestamp, or what looks like one, anywhere in a line.
		internal val ACTIVE = Regex("""<\d{4}-\d{2}-\d{2}[^<>\n]*>""")

		private fun inWindow(d: LocalDate, from: LocalDate, to: LocalDate): Boolean = !d.isBefore(from) && !d.isAfter(to)

		// The text of an Org time: "09:00".
		private fun hhmm(t: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)

		// [date] plus [months] as Emacs's encode-time counts it: the day past a month's end runs into
		// the next month (Jan 31 plus a month is Mar 3 in a common year).
		internal fun addMonths(date: LocalDate, months: Long): LocalDate =
			date.withDayOfMonth(1).plusMonths(months).plusDays((date.dayOfMonth - 1).toLong())

		private fun time(h: String, m: String): LocalTime? = try {
			LocalTime.of(h.toInt(), m.toInt())
		} catch (e: DateTimeException) {
			null
		}

		// Parse one whole timestamp, active or inactive. Null for any other text, incl. a diary sexp, a
		// range, or a date or time that does not exist.
		fun parse(text: String): Timestamp? {
			val m = SHAPE.matchEntire(text) ?: return null
			val active = m.groupValues[1] == "<"
			if (active != (m.groupValues[6] == ">")) return null
			val date = try {
				LocalDate.of(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
			} catch (e: DateTimeException) {
				return null
			}
			val rest = m.groupValues[5]
			if (rest.isNotEmpty() && !OrgDocument.isBlankChar(rest[0])) return null
			var start: LocalTime? = null
			var end: LocalTime? = null
			var repeater: Repeater? = null
			var warning: String? = null
			var warningFirst = false
			val tokens = rest.split(' ', '\t').filter { it.isNotEmpty() }
			for ((i, tok) in tokens.withIndex()) {
				val tm = TIME.matchEntire(tok)
				val rp = Repeater.parse(tok)
				when {
					i == 0 && DAY_NAME.matches(tok) -> Unit
					tm != null && start == null -> {
						start = time(tm.groupValues[1], tm.groupValues[2]) ?: return null
						if (tm.groupValues[3].isNotEmpty()) end = time(tm.groupValues[3], tm.groupValues[4]) ?: return null
					}
					rp != null && repeater == null -> repeater = rp
					WARNING.matches(tok) && warning == null -> {
						warning = tok
						warningFirst = repeater == null
					}
					else -> return null
				}
			}
			if (repeater == null) warningFirst = false
			return Timestamp(date, start, end, repeater, warning, active, warningFirst)
		}

		// The shape of a line in the timestamp block of calendar.org, or null when the line does not
		// begin w/ a timestamp (it then begins the body). Only a line that holds one active timestamp,
		// w/o a habit repeater, and naught else reads.
		fun lineShape(text: String): LineShape? = readLine(text)?.shape

		// A timestamp line read once: its shape and its stamp (null when the text does not parse; an
		// inactive or a habit stamp parses yet has the shape UNREADABLE). Null when the line does not
		// begin w/ a timestamp.
		fun readLine(text: String): TimestampLine? {
			val t = text.trim { OrgDocument.isBlankChar(it) }
			if (!LINE_START.matches(t)) return null
			val ts = parse(t)
			val shape = if (ts != null && ts.active && ts.repeater?.habit == null) LineShape.READABLE else LineShape.UNREADABLE
			return TimestampLine(shape, ts)
		}

		// True when [text] holds an active timestamp or a diary sexp anywhere, as a title may.
		internal fun containsActive(text: String): Boolean = ACTIVE.containsMatchIn(text) || text.contains("<%%(")
	}
}
