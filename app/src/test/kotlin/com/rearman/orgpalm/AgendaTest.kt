package com.rearman.orgpalm

import com.rearman.orgpalm.org.OrgFile
import com.rearman.orgpalm.org.OrgSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AgendaTest {
	// Today's untimed rows sort by Org's urgency: priority first (1000 a step), then a SCHEDULED line
	// (99 + days since), a missed deadline (its days overdue), a plain stamp (naught), & a coming
	// deadline (less its days to go).
	@Test
	fun todaySortsByUrgency() {
		val today = LocalDate.of(2026, 11, 24)
		val text = """
			* TODO Coming deadline
			DEADLINE: <2026-11-29 Sun>
			* Plain stamp
			<2026-11-24 Tue>
			* TODO Missed deadline
			DEADLINE: <2026-11-21 Sat>
			* TODO Scheduled today
			SCHEDULED: <2026-11-24 Tue>
			* [#1] Urgent stamp
			<2026-11-24 Tue>
		""".trimIndent() + "\n"
		val file = OrgFile.parse("a.org", text, OrgSettings.STARTING)
		val rows = Agenda.days(listOf(file), today, today, today).single().rows
		assertEquals(
			listOf("Urgent stamp", "Scheduled today", "Missed deadline", "Plain stamp", "Coming deadline"),
			rows.map { it.entry.title },
		)
	}
}
