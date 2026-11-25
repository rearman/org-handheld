package com.rearman.orgpalm

import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.OrgFile
import com.rearman.orgpalm.org.Timestamp
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// One line of the agenda: the file & the entry, the time span at which it shows on its day (none for
// an untimed one or in the todo list), Org's priority & urgency values (higher ranks first), & Org's
// leaders ("Scheduled:", "Deadline:", "Sched.6x:", "3 d. ago:", "In 5 d.:"). [early] marks a row that
// only a coming deadline's warning puts on its day, which the Day & Week grids leave out.
data class AgendaRow(
	val file: OrgFile,
	val entry: Entry,
	val time: LocalTime?,
	val priority: Int,
	val urgency: Int,
	val leaders: List<String> = emptyList(),
	val end: LocalTime? = null,
	val early: Boolean = false,
)

data class AgendaDay(val date: LocalDate, val rows: List<AgendaRow>)

// The Agenda zoom level: one block per day of the span, then the todo list, as Org's agenda w/ the
// todo list (C-c a n) shows them.
data class Agenda(val days: List<AgendaDay>, val todos: List<AgendaRow>) {
	companion object {
		// Org's org-agenda-span 'week.
		const val SPAN = 7L

		// The span from [from], which starts at today as org-agenda-start-on-weekday nil starts it. The
		// todo list sorts as Org's todo strategy does (urgency-down, then file order), its urgency one
		// above the priority, as org-agenda-get-todos sets it.
		fun of(files: List<OrgFile>, from: LocalDate, today: LocalDate): Agenda {
			val todos = files.flatMap { file ->
				file.entries.filter { it.inTodoList }.map { val p = priority(it, file); AgendaRow(file, it, null, p, p + 1) }
			}
			return Agenda(days(files, from, from.plusDays(SPAN - 1), today), todos.sortedByDescending { it.urgency })
		}

		// Each day from [from] to [to] w/ its rows, sorted as Org's default agenda strategy sorts them
		// (time-up, urgency-down, then file order).
		fun days(files: List<OrgFile>, from: LocalDate, to: LocalDate, today: LocalDate): List<AgendaDay> {
			val byDate = LinkedHashMap<LocalDate, ArrayList<AgendaRow>>()
			var d = from
			while (!d.isAfter(to)) {
				byDate[d] = ArrayList()
				d = d.plusDays(1)
			}
			for (file in files) {
				val warningDays = file.settings.deadlineWarningDays
				for (e in file.entries) {
					val priority = priority(e, file)
					for (date in e.agendaDates(from, to, today, warningDays)) {
						val stamp = stampOn(e, date)
						val urgency = urgency(e, date, today, warningDays, priority)
						byDate[date]?.add(
							AgendaRow(file, e, stamp?.start, priority, urgency, leaders(e, date, today, warningDays), stamp?.end, early(e, date, today, warningDays)),
						)
					}
				}
			}
			val dayOrder = compareBy<AgendaRow, LocalTime?>(nullsLast()) { it.time }.thenByDescending { it.urgency }
			return byDate.map { (date, rows) -> AgendaDay(date, rows.sortedWith(dayOrder)) }
		}

		// Org's leaders for an entry on [d], w/o their padding (org-agenda-scheduled-leaders &
		// org-agenda-deadline-leaders): on today, "Sched.Nx:" for a missed SCHEDULED date, "N d. ago:"
		// for a missed DEADLINE, & "In N d.:" for a deadline within its warning days, for an entry not
		// done; on the date of a stamp or a repeat, "Scheduled:" & "Deadline:". Org shows the scheduled &
		// the deadline item as two lines; here one row carries both leaders.
		private fun leaders(e: Entry, d: LocalDate, today: LocalDate, warningDays: Int): List<String> {
			val due = d == today && !e.done
			val out = ArrayList<String>()
			e.scheduled?.let {
				when {
					due && it.pastDue(today, scheduled = true) -> out.add("Sched.${ChronoUnit.DAYS.between(it.date, today)}x:")
					it.planningDates(d, d, scheduled = true).isNotEmpty() -> out.add("Scheduled:")
				}
			}
			e.deadline?.let {
				when {
					due && it.pastDue(today, scheduled = false) -> out.add("${ChronoUnit.DAYS.between(it.date, today)} d. ago:")
					due && it.dueSoon(today, warningDays) -> out.add("In ${ChronoUnit.DAYS.between(today, it.date)} d.:")
					it.planningDates(d, d, scheduled = false).isNotEmpty() -> out.add("Deadline:")
				}
			}
			return out
		}

		// Whether a coming deadline within its warning days is the only thing that puts [e] on [d]: no
		// SCHEDULED date on [d] nor missed, no DEADLINE on [d], & no stamp, range, or sexp on [d].
		private fun early(e: Entry, d: LocalDate, today: LocalDate, warningDays: Int): Boolean {
			val deadline = e.deadline ?: return false
			if (d != today || e.done || !deadline.dueSoon(today, warningDays)) return false
			if (deadline.planningDates(d, d, scheduled = false).isNotEmpty()) return false
			val scheduled = e.scheduled?.let { it.planningDates(d, d, scheduled = true).isNotEmpty() || it.pastDue(today, scheduled = true) } == true
			return !scheduled && !stamped(e, d)
		}

		// Whether a stamp, a range, or a sexp of [e]'s text falls on [d].
		private fun stamped(e: Entry, d: LocalDate): Boolean =
			e.activeStamps.any { it.occurrences(d, d).isNotEmpty() } || e.ranges.any { d in it } || e.sexps.any { it.dates(d, d).isNotEmpty() }

		// Org's urgency of the entry's line on [d], from its priority value [p]: a SCHEDULED line
		// 99 + the days since the written date + p (org-agenda-get-scheduled), a DEADLINE line p, plus
		// on today the days since the written date (org-agenda-get-deadlines), & a timestamp, range, or
		// sexp p. Org shows each kind as a line of its own; the one row here takes the highest.
		private fun urgency(e: Entry, d: LocalDate, today: LocalDate, warningDays: Int, p: Int): Int {
			val lines = ArrayList<Int>()
			val due = d == today && !e.done
			e.scheduled?.let {
				if (it.planningDates(d, d, scheduled = true).isNotEmpty() || (due && it.pastDue(today, scheduled = true))) {
					lines.add(99 + ChronoUnit.DAYS.between(it.date, d).toInt() + p)
				}
			}
			e.deadline?.let {
				val shown = it.planningDates(d, d, scheduled = false).isNotEmpty() ||
					(due && (it.pastDue(today, scheduled = false) || it.dueSoon(today, warningDays)))
				if (shown) lines.add(p + if (d == today) ChronoUnit.DAYS.between(it.date, today).toInt() else 0)
			}
			if (stamped(e, d) || lines.isEmpty()) lines.add(p)
			return lines.max()
		}

		// The timed stamp that puts [e] on [d]: its SCHEDULED, its DEADLINE, or an active stamp of its
		// text, in that order. A range or a sexp gives none, nor does a missed or coming date shown on
		// today, as in Org.
		fun stampOn(e: Entry, d: LocalDate): Timestamp? {
			e.scheduled?.takeIf { it.start != null && it.planningDates(d, d, scheduled = true).isNotEmpty() }?.let { return it }
			e.deadline?.takeIf { it.start != null && it.planningDates(d, d, scheduled = false).isNotEmpty() }?.let { return it }
			return e.activeStamps.firstOrNull { it.start != null && it.occurrences(d, d).isNotEmpty() }
		}

		// Org's priority value, as org-get-priority gives it: 1000 times the distance of the entry's
		// cookie (org-priority-to-value: a number, else a letter's code), or of the default for none,
		// from the file's lowest priority. A higher value ranks first.
		fun priority(e: Entry, file: OrgFile): Int {
			val value = e.priority?.let { it.toIntOrNull() ?: it[0].code } ?: file.priorities.default
			return 1000 * (file.priorities.lowest - value)
		}
	}
}
