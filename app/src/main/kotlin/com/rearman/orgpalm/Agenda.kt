package com.rearman.orgpalm

import com.rearman.orgpalm.org.Entry
import com.rearman.orgpalm.org.OrgFile
import java.time.LocalDate
import java.time.LocalTime

// One line of the agenda: the entry, the time at which it shows on its day (none for an untimed one or
// in the todo list), & its priority's value, where a smaller value ranks higher.
data class AgendaRow(val entry: Entry, val time: LocalTime?, val priority: Int)

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
				for (e in file.entries) {
					val priority = priority(e, file)
					for (d in e.agendaDates(today, last)) byDate[d]?.add(AgendaRow(e, timeOn(e, d), priority))
					if (e.inTodoList) todos.add(AgendaRow(e, null, priority))
				}
			}
			val dayOrder = compareBy<AgendaRow, LocalTime?>(nullsLast()) { it.time }.thenBy { it.priority }
			return Agenda(
				byDate.map { (d, rows) -> AgendaDay(d, rows.sortedWith(dayOrder)) },
				todos.sortedBy { it.priority },
			)
		}

		// The start time of the stamp that puts [e] on [d]: its SCHEDULED, its DEADLINE, or an active
		// stamp of its text, in that order. A range or a sexp gives no time.
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
