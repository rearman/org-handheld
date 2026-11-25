package com.rearman.orgpalm.org

import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// A diary sexp, of a timestamp (<%%(...)>) or of a line that starts w/ one (%%(...) text), in one of
// the forms the app models (DESIGN.md section 3): diary-anniversary, diary-block, diary-cyclic,
// diary-date, and diary-float, w/ their dates in the american order (month, day, year) of
// calendar-date-style's default, and org-anniversary, w/ its date in the iso order (year, month, day).
// parse() gives null for any other sexp, which shows on no date.
class DiarySexp private constructor(private val test: (LocalDate) -> Boolean) {
	fun matches(date: LocalDate): Boolean = test(date)

	// The dates from [from] to [to] on which the sexp applies, as Org's agenda evaluates it once per
	// day. Limit: one test per day of the window, cheap for an agenda span; a wide window over many
	// sexps would want a closed form per kind.
	fun dates(from: LocalDate, to: LocalDate): List<LocalDate> {
		val out = ArrayList<LocalDate>()
		var d = from
		while (!d.isAfter(to)) {
			if (test(d)) out.add(d)
			d = d.plusDays(1)
		}
		return out
	}

	// One argument: a number, t (every value), nil, a quoted list of numbers, or aught else (a mark).
	private sealed interface Arg {
		data class Num(val n: Int) : Arg
		data class Nums(val ns: Set<Int>) : Arg
		data object All : Arg
		data object Nil : Arg
		data object Other : Arg
	}

	companion object {
		// The text of a sexp timestamp: group 1 is the sexp w/o its outer parentheses.
		internal val STAMP = Regex("""<%%\(([^>\n]*)\)>""")

		// A diary sexp line, as org-agenda-get-sexps finds it: "%%(" at the start of the line, after an
		// optional "&".
		private val LINE = Regex("""&?%%\(.*""", RegexOption.DOT_MATCHES_ALL)

		// The sexp of a diary sexp line w/o its outer parentheses, up to the parenthesis that closes it,
		// or null for any other line or a sexp left open.
		fun lineSexp(line: String): String? {
			if (!LINE.matches(line)) return null
			val open = line.indexOf('(')
			var depth = 0
			var quoted = false
			for (i in open until line.length) {
				val c = line[i]
				when {
					c == '"' -> quoted = !quoted
					quoted -> {}
					c == '(' -> depth++
					c == ')' -> if (--depth == 0) return line.substring(open + 1, i)
				}
			}
			return null
		}

		private val TOKEN = Regex(""""[^"]*"|'\([^()]*\)|[^\s()"']+""")
		private val INT = Regex("""-?\d+""")

		private fun arg(token: String): Arg = when {
			INT.matches(token) -> token.toIntOrNull()?.let { Arg.Num(it) } ?: Arg.Other
			token == "t" -> Arg.All
			token == "nil" -> Arg.Nil
			token.startsWith("'(") -> {
				val ns = OrgDocument.words(token.substring(2, token.length - 1)).map { it.toIntOrNull() }
				if (ns.any { it == null }) Arg.Other else Arg.Nums(ns.filterNotNull().toSet())
			}
			else -> Arg.Other
		}

		// A value test of diary-date and diary-float: a number, a list, or t.
		private fun accepts(a: Arg, value: Int): Boolean? = when (a) {
			is Arg.Num -> a.n == value
			is Arg.Nums -> value in a.ns
			Arg.All -> true
			else -> null
		}

		// The date of month [m], day [d], year [y], a day past the month's end running into the next
		// month, as calendar-absolute-from-gregorian counts it.
		private fun date(m: Int, d: Int, y: Int): LocalDate = LocalDate.of(y, m, 1).plusDays((d - 1).toLong())

		// Sunday 0 to Saturday 6, as calendar-day-of-week counts.
		private fun weekdayOf(d: LocalDate): Int = d.dayOfWeek.value % 7

		// The [n]th day named [dayName] on or after [base] (n > 0), or on or before it (n < 0), as
		// calendar-nth-named-absday finds it.
		private fun nthNamed(n: Int, dayName: Int, base: LocalDate): LocalDate =
			if (n > 0) base.plusDays(((dayName - weekdayOf(base) + 7) % 7 + 7L * (n - 1)))
			else base.minusDays(((weekdayOf(base) - dayName + 7) % 7 + 7L * (-n - 1)))

		private fun num(a: Arg?): Int? = (a as? Arg.Num)?.n

		// True when [args] holds [required] arguments and up to [optional] more (a year, a day, or a mark).
		private fun fits(args: List<Arg>, required: Int, optional: Int): Boolean = args.size in required..required + optional

		// The sexp w/o its outer parentheses, as STAMP gives it: "diary-float t 4 2".
		fun parse(sexp: String): DiarySexp? {
			if (sexp.replace(TOKEN, "").isNotBlank()) return null
			val tokens = TOKEN.findAll(sexp).map { it.value }.toList()
			if (tokens.isEmpty()) return null
			val a = tokens.drop(1).map { arg(it) }
			return try {
				when (tokens[0]) {
					"diary-date" -> if (!fits(a, 3, 1)) null else {
						val (m, d, y) = a
						if (listOf(m, d, y).any { accepts(it, 1) == null }) null
						else DiarySexp { t -> accepts(m, t.monthValue) == true && accepts(d, t.dayOfMonth) == true && accepts(y, t.year) == true }
					}
					"diary-block" -> {
						val n = a.take(6).map { num(it) }
						if (!fits(a, 6, 1) || n.any { it == null }) null else {
							val from = date(n[0]!!, n[1]!!, n[2]!!)
							val to = date(n[3]!!, n[4]!!, n[5]!!)
							DiarySexp { t -> !t.isBefore(from) && !t.isAfter(to) }
						}
					}
					"diary-anniversary" -> {
						val mm = num(a.getOrNull(0))
						val dd = num(a.getOrNull(1))
						if (!fits(a, 2, 2) || mm == null || dd == null) null else anniversary(mm, dd, num(a.getOrNull(2)))
					}
					// org-anniversary binds calendar-date-style to iso & calls diary-anniversary.
					"org-anniversary" -> {
						val yy = num(a.getOrNull(0))
						val mm = num(a.getOrNull(1))
						val dd = num(a.getOrNull(2))
						if (!fits(a, 3, 1) || yy == null || mm == null || dd == null) null else anniversary(mm, dd, yy)
					}
					"diary-cyclic" -> {
						val n = a.take(4).map { num(it) }
						if (!fits(a, 4, 1) || n.any { it == null } || n[0]!! <= 0) null else {
							val start = date(n[1]!!, n[2]!!, n[3]!!)
							val step = n[0]!!.toLong()
							DiarySexp { t ->
								val diff = ChronoUnit.DAYS.between(start, t)
								diff >= 0 && diff % step == 0L
							}
						}
					}
					"diary-float" -> {
						val month = a.getOrNull(0)
						val dayName = num(a.getOrNull(1))
						val n = num(a.getOrNull(2))
						val day = num(a.getOrNull(3))
						if (!fits(a, 3, 2) || month == null || accepts(month, 1) == null || dayName == null || dayName !in 0..6 || n == null || n == 0) null
						else DiarySexp { t -> weekdayOf(t) == dayName && floats(month, dayName, n, day, t) }
					}
					else -> null
				}
			} catch (e: DateTimeException) {
				null
			}
		}

		// diary-anniversary of month [mm], day [dd], & year [yy]: each date after that year (in every year
		// w/o one) whose month & day match.
		private fun anniversary(mm: Int, dd: Int, yy: Int?): DiarySexp = DiarySexp { t ->
			val diff = if (yy == null) 100 else t.year - yy
			// The anniversary of February 29 is March 1 in a common year.
			val leapDay = mm == 2 && dd == 29 && !t.isLeapYear
			diff > 0 && t.monthValue == (if (leapDay) 3 else mm) && t.dayOfMonth == (if (leapDay) 1 else dd)
		}

		// diary-float on [t]: some month of [month] near t whose base date (DAY, else the first of the
		// month for n > 0 and its last day for n < 0) has t as its nth [dayName]. The base months
		// searched reach |n| weeks and two months each way, past any base date that could reach t.
		private fun floats(month: Arg, dayName: Int, n: Int, day: Int?, t: LocalDate): Boolean {
			val reach = Math.abs(n) / 4 + 2L
			val first = t.withDayOfMonth(1)
			for (k in -reach..reach) {
				val m = first.plusMonths(k)
				if (accepts(month, m.monthValue) != true) continue
				val baseDay = day ?: if (n > 0) 1 else m.lengthOfMonth()
				if (nthNamed(n, dayName, m.plusDays(baseDay - 1L)) == t) return true
			}
			return false
		}
	}
}
