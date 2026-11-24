package com.rearman.orgpalm

import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.OrgFile
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// One line of the agenda: the entry, the time at which it shows on its day (none for an untimed one or
// in the todo list), its priority's value, where a smaller value ranks higher, & Org's leaders
// ("Scheduled:", "Deadline:", "Sched.6x:", "3 d. ago:", "In 5 d.:").
data class AgendaRow(val entry: Entry, val time: LocalTime?, val priority: Int, val leaders: List<String> = emptyList())

data class AgendaDay(val date: LocalDate, val rows: List<AgendaRow>)

// The Agenda zoom level: one block per day of the span, then the todo list, as Org's agenda w/ the
// todo list (C-c a n) shows them.
data class Agenda(val days: List<AgendaDay>, val todos: List<AgendaRow>) {
	companion object {
		// Org's org-agenda-span 'week, from today, as org-agenda-start-on-weekday nil starts it.
		const val SPAN = 7L

		// The days sort as Org's default agenda strategy sorts them (time-up, priority-down, then file
		// order); the todo list as its todo strategy does (priority-down, then file order).
		fun of(files: List<OrgFile>, today: LocalDate): Agenda {
			val last = today.plusDays(SPAN - 1)
			val byDate = (0 until SPAN).associate { today.plusDays(it) to ArrayList<AgendaRow>() }
			val todos = ArrayList<AgendaRow>()
			for (file in files) {
				val warningDays = file.settings.deadlineWarningDays
				for (e in file.entries) {
					val priority = priority(e, file)
					for (d in e.agendaDates(today, last, today, warningDays)) {
						byDate[d]?.add(AgendaRow(e, timeOn(e, d), priority, leaders(e, d, today, warningDays)))
					}
					if (e.inTodoList) todos.add(AgendaRow(e, null, priority))
				}
			}
			val dayOrder = compareBy<AgendaRow, LocalTime?>(nullsLast()) { it.time }.thenBy { it.priority }
			return Agenda(
				byDate.map { (d, rows) -> AgendaDay(d, rows.sortedWith(dayOrder)) },
				todos.sortedBy { it.priority },
			)
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

		// The start time of the stamp that puts [e] on [d]: its SCHEDULED, its DEADLINE, or an active
		// stamp of its text, in that order. A range or a sexp gives no time, nor does a missed or coming
		// date shown on today, as in Org.
		private fun timeOn(e: Entry, d: LocalDate): LocalTime? {
			e.scheduled?.takeIf { it.planningDates(d, d, scheduled = true).isNotEmpty() }?.start?.let { return it }
			e.deadline?.takeIf { it.planningDates(d, d, scheduled = false).isNotEmpty() }?.start?.let { return it }
			return e.activeStamps.firstOrNull { it.start != null && it.occurrences(d, d).isNotEmpty() }?.start
		}

		// The value of the entry's cookie as org-priority-to-value reads it (a number, else a letter's
		// code), or the file's default priority for none.
		private fun priority(e: Entry, file: OrgFile): Int =
			e.priority?.let { it.toIntOrNull() ?: it[0].code } ?: file.priorities.default
	}
}
