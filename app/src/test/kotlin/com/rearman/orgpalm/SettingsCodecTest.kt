package com.rearman.orgpalm

import com.rearman.orgpalm.org.OrgSettings
import com.rearman.orgpalm.org.TodoSequence
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {
	// The stored text of the keywords reads back as the same sequences, log marks & done keywords kept.
	@Test
	fun keywordsReadBackAsStored() {
		val sequences = OrgSettings.STARTING.todoKeywords + TodoSequence.parse("BUG FIXED(/!)", type = true)
		assertEquals(sequences, SettingsCodec.keywords(SettingsCodec.keywordsText(sequences)))
	}
}
