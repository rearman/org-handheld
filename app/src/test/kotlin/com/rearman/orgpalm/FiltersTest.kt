package com.rearman.orgpalm

import com.rearman.orgpalm.org.OrgFile
import com.rearman.orgpalm.org.OrgSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FiltersTest {
	private val today = LocalDate.of(2026, 11, 24)

	private fun titles(block: Block, text: String): List<String> =
		Filters.rows(block, listOf(OrgFile.parse("a.org", text, OrgSettings.STARTING)), today).rows.map { it.second.entry.title }

	// A project (tagged undertaking, not DONE or BELAYED) is stuck w/o a NEXT or WAITING heading in its
	// subtree, its own heading included, as org-stuck-projects in init.el gives it.
	@Test
	fun stuckProjectsLackANextOrWaitingHeading() {
		val text = """
			* Pump skid :undertaking:
			** TODO Order seals
			* Panel retrofit :undertaking:
			** NEXT Pull wire
			* WAITING Vendor quote :undertaking:
			* DONE Old job :undertaking:
			* BELAYED Shelved job :undertaking:
			* Plain heading
			** TODO Child
		""".trimIndent() + "\n"
		assertEquals(listOf("Pump skid"), titles(Block("STUCK", stuck = true), text))
	}

	// Contexts sorts by the last tag (none last), then priority, then effort (none as high), as
	// tag-up, priority-down, effort-up give it.
	@Test
	fun contextsSortByTagThenPriorityThenEffort() {
		val text = """
			* NEXT Untagged
			* NEXT Call slow :call:
			:PROPERTIES:
			:Effort: 1:30
			:END:
			* NEXT Call quick :call:
			:PROPERTIES:
			:Effort: 0:15
			:END:
			* NEXT [#1] Call urgent :call:
			* NEXT Buy fuses :errand:
			* TODO Not next :call:
		""".trimIndent() + "\n"
		assertEquals(
			listOf("Call urgent", "Call quick", "Call slow", "Buy fuses", "Untagged"),
			titles(Filters.STARTING[1].blocks.single(), text),
		)
	}
}
