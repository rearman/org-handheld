package com.rearman.orgpalm

import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.OrgFile
import com.rearman.orgpalm.org.Timestamp
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// One line of the agenda: the file // One line of the agenda: the entry, the time span the entry, the time span at which it shows on its day (none for an untimed
// one or in the todo list), its priority's value, where a smaller value ranks higher, & Org's leaders
// ("Scheduled:", "Deadline:", "Sched.6x:", "3 d. ago:", "In 5 d.:").
data class AgendaRow(
	val file: OrgFile,
	val entry: Entry,
	val time: LocalTime?,
	val priority: Int,
	val leaders: List<String> = emptyList(),
	val end: LocalTime? = null,
)

data class AgendaDay(val date: LocalDate, val rows: List<AgendaRow>)

// The Agenda zoom level: one block per day of the span, then the todo list, as Org's agenda w/ the
// todo list (C-c a n) shows them.
data class Agenda(val days: List<AgendaDay>, val todos: List<AgendaRow>) {
	companion object {
		// Org's org-agenda-span 'week.
		const val SPAN = 7L

		// The span from [from], which starts at today as org-agenda-start-on-weekday nil starts it. The
		// todo list sorts as Org's todo strategy does (priority-down, then file order).
		fun of(files: List<OrgFile>, from: LocalDate, today: LocalDate): Agenda {
			val todos = files.flatMap { file -> file.entries.filter { it.inTodoList }.map { AgendaRow(file, it, null, priority(it, file)) } }
			return Agenda(days(files, from, from.plusDays(SPAN - 1), today), todos.sortedBy { it.priority })
		}

		// Each day from [from] to [to] w/ its rows, sorted as Org's default agenda strategy sorts them
		// (time-up, priority-down, then file order).
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
						byDate[date]?.add(AgendaRow(file, e, stamp?.start, priority, leaders(e, date, today, warningDays), stamp?.end))
					}
				}
			}
			val dayOrder = compareBy<AgendaRow, LocalTime?>(nullsLast()) { it.time }.thenBy { it.priority }
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

		// The timed stamp that puts [e] on [d]: its SCHEDULED, its DEADLINE, or an active stamp of its
		// text, in that order. A range or a sexp gives none, nor does a missed or coming date shown on
		// today, as in Org.
		private fun stampOn(e: Entry, d: LocalDate): Timestamp? {
			e.scheduled?.takeIf { it.start != null && it.planningDates(d, d, scheduled = true).isNotEmpty() }?.let { return it }
			e.deadline?.takeIf { it.start != null && it.planningDates(d, d, scheduled = false).isNotEmpty() }?.let { return it }
			return e.activeStamps.firstOrNull { it.start != null && it.occurrences(d, d).isNotEmpty() }
		}

		// The value of the entry's cookie as org-priority-to-value reads it (a number, else a letter's
		// code), or the file's default priority for none.
		private fun priority(e: Entry, file: OrgFile): Int =
			e.priority?.let { it.toIntOrNull() ?: it[0].code } ?: file.priorities.default
	}
}
