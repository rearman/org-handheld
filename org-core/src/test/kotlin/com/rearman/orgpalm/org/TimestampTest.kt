package com.rearman.orgpalm.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class TimestampTest {
	@Test
	fun parsesEachPartAndFormatsItBackAsWritten() {
		val forms = listOf(
			"<2026-11-23 Mon 09:00-10:00>", "<2026-11-26 Thu>", "<2026-11-23 Mon 07:00-08:00 +2w>",
			"<2026-01-31 Sat .+1m>", "<2026-01-31 Sat ++1y -3d>", "[2026-11-20 Fri 16:42]",
			"<2026-11-25 Wed -2d +1w>",
		)
		for (f in forms) assertEquals(f, Fixtures.ts(f).format())
		val t = Fixtures.ts("<2026-11-23 Mon 09:00-10:00 +2w -1d>")
		assertEquals(LocalDate.of(2026, 11, 23), t.date)
		assertEquals(LocalTime.of(9, 0), t.start)
		assertEquals(LocalTime.of(10, 0), t.end)
		assertEquals(Repeater("+", 2, RepeatUnit.WEEK), t.repeater)
		assertEquals("-1d", t.warning)
		assertEquals(true, t.active)
	}

	@Test
	fun formatIgnoresTheDefaultLocale() {
		val saved = Locale.getDefault()
		try {
			Locale.setDefault(Locale.forLanguageTag("ar-EG"))
			assertEquals("<2026-11-23 Mon 09:05-10:00>", Fixtures.ts("<2026-11-23 Mon 09:05-10:00>").format())
			val text = Fixtures.text("todo.org")
			val done = Fixtures.writer().apply(text, Edit.Update(Fixtures.anchor(text, Fixtures.VFD), listOf(FieldChange.State("DONE"))))
			assertEquals(Fixtures.text("todo-done.expected.org"), (done as WriteResult.Written).text)
		} finally {
			Locale.setDefault(saved)
		}
	}

	// A stamp line holds one active timestamp and naught else (DESIGN.md section 3, Events).
	@Test
	fun stampLinesHoldOneActiveStampAlone() {
		assertEquals(Fixtures.ts("<2026-11-23 Mon 07:00-08:00 +2w>"), Section.stampOf("  <2026-11-23 Mon 07:00-08:00 +2w>"))
		for (t in listOf(
			"<%%(diary-float t 3 3)>", "<2026-12-21 Mon>--<2026-12-23 Wed>", "<2026-02-30 Mon>", "<2026-11-23 Mon> <2026-11-26 Thu>",
			"<2026-11-23 Mon> call the rep", "[2026-11-20 Fri]", "Call <2026-11-23 Mon> first",
		)) assertNull(t, Section.stampOf(t))
	}

	// repeats.txt holds, for each timestamp, the dates on which Emacs's agenda shows it as a plain
	// timestamp or on a SCHEDULED or DEADLINE line, incl. monthly and yearly repeats from the 29th to
	// the 31st, windows that open long after the first date, a warning ahead of the repeater, a zero
	// count, and hours.
	@Test
	fun repeatsFallOnTheDatesEmacsShows() {
		var rows = 0
		for (line in Fixtures.text("repeats.txt").lines()) {
			if (line.isEmpty() || line.startsWith("#")) continue
			val (kind, ts, from, to, dates) = line.split('\t')
			val expected = dates.split(' ').filter { it.isNotEmpty() }.map { LocalDate.parse(it) }
			val stamp = Fixtures.ts(ts)
			val got = when (kind) {
				"plain" -> stamp.occurrences(LocalDate.parse(from), LocalDate.parse(to))
				else -> stamp.planningDates(LocalDate.parse(from), LocalDate.parse(to), kind == "scheduled")
			}
			assertEquals("$kind $ts", expected, got)
			rows++
		}
		assertEquals(31, rows)
	}
}
