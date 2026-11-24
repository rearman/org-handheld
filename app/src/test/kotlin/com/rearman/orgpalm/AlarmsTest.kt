package com.rearman.orgpalm

import com.rearman.orgpalm.org.OrgFile
import com.rearman.orgpalm.org.OrgSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class AlarmsTest {
	// Each entry w/ :APPT_WARNTIME: rings that many minutes ere its next timed occurrence after now; one
	// w/o the property, or w/o a time, rings never.
	@Test
	fun nextAlarmPerEntry() {
		val text = """
			* Compressor PM
			:PROPERTIES:
			:APPT_WARNTIME: 15
			:END:
			<2026-11-23 Mon 09:00 +1w>
			* Panel walkdown
			<2026-11-24 Tue 13:30>
			* All-day
			:PROPERTIES:
			:APPT_WARNTIME: 10
			:END:
			<2026-11-25 Wed>
		""".trimIndent() + "\n"
		val file = OrgFile.parse("a.org", text, OrgSettings.STARTING)
		val alarms = Alarms.next(listOf(file), LocalDateTime.of(2026, 11, 23, 10, 0))
		assertEquals(listOf("Compressor PM" to LocalDateTime.of(2026, 11, 30, 8, 45)), alarms.map { it.title to it.at })
	}
}
