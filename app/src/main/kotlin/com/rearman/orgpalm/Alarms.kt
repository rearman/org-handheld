package com.rearman.orgpalm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.rearman.orgpalm.org.FieldChange
import com.rearman.orgpalm.org.OrgFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The alarms of PLAN.md phase 6: an entry w/ :APPT_WARNTIME: warns that many minutes ere the start of
// each timed occurrence, as org-agenda-to-appt reads it; an entry w/o it has no alarm (DESIGN.md
// section 3, Entries). Each entry keeps one exact alarm, set for its next occurrence.
object Alarms {
	private const val CHANNEL = "alarms"
	private const val PREFS = "alarms"
	private const val IDS = "ids"
	private const val EXTRA_TITLE = "title"
	private const val EXTRA_TEXT = "text"

	// How far ahead the next occurrence is sought. Limit: a repeat rarer than yearly, or a first date
	// further out, gets no alarm until the window reaches it; a search per repeater is the upgrade.
	const val WINDOW_DAYS = 366L

	private val TIME = DateTimeFormatter.ofPattern("HH:mm")

	// One alarm: its id (stable per file & heading line), when it rings, when the event starts, & the
	// entry's title.
	data class Alarm(val id: Int, val at: LocalDateTime, val start: LocalDateTime, val title: String)

	// The next alarm after [now] of each entry w/ :APPT_WARNTIME:, from the timed stamps that put it
	// on the agenda (Agenda.stampOn).
	fun next(files: List<OrgFile>, now: LocalDateTime): List<Alarm> {
		val out = ArrayList<Alarm>()
		val today = now.toLocalDate()
		for (file in files) {
			for (e in file.entries) {
				val warn = e.property(FieldChange.WARN_TIME)?.trim()?.toIntOrNull() ?: continue
				for (d in e.agendaDates(today, today.plusDays(WINDOW_DAYS))) {
					val time = Agenda.stampOn(e, d)?.start ?: continue
					val start = d.atTime(time)
					val at = start.minusMinutes(warn.toLong())
					if (at.isAfter(now)) {
						out.add(Alarm((file.path + "\n" + e.anchor.headingLine).hashCode(), at, start, e.title))
						break
					}
				}
			}
		}
		return out
	}

	// Cancels the alarms set last time & sets one per entry for [files].
	fun schedule(context: Context, files: List<OrgFile>) {
		val am = context.getSystemService(AlarmManager::class.java)
		val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		for (old in prefs.getStringSet(IDS, emptySet()).orEmpty()) am.cancel(pending(context, old.toInt(), null))
		val alarms = next(files, LocalDateTime.now())
		for (a in alarms) {
			val millis = a.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
			val pi = pending(context, a.id, a)
			// W/o the exact-alarm grant (API 31 & up), the alarm rings inexactly rather than not at all.
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
				am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
			} else {
				am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
			}
		}
		prefs.edit().putStringSet(IDS, alarms.map { it.id.toString() }.toSet()).apply()
	}

	// Reads the folders fresh & schedules, for a receiver that runs w/o the app's model.
	suspend fun scheduleFromDisk(context: Context) {
		val stores = Stores(context)
		val storage = Storage(context.contentResolver)
		val settings = stores.settings.first()
		val files = stores.folders.first().flatMap { folder ->
			storage.orgFiles(folder).map { (uri, found) -> OrgFile.parse(found.path, storage.read(uri), settings) }
		}
		schedule(context, files)
	}

	private fun pending(context: Context, id: Int, alarm: Alarm?): PendingIntent {
		val intent = Intent(context, AlarmReceiver::class.java)
		alarm?.let {
			intent.putExtra(EXTRA_TITLE, it.title)
			intent.putExtra(EXTRA_TEXT, "At ${TIME.format(it.start)}")
		}
		return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
	}

	fun post(context: Context, intent: Intent) {
		val nm = context.getSystemService(NotificationManager::class.java)
		nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH))
		val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
		val title = intent.getStringExtra(EXTRA_TITLE) ?: return
		val n = Notification.Builder(context, CHANNEL)
			.setSmallIcon(android.R.drawable.ic_popup_reminder)
			.setContentTitle(title)
			.setContentText(intent.getStringExtra(EXTRA_TEXT))
			.setContentIntent(open)
			.setAutoCancel(true)
			.build()
		nm.notify(title.hashCode(), n)
	}

	// Runs [Alarms.scheduleFromDisk] off the main thread for the length of a receiver's goAsync.
	fun rescheduleAsync(receiver: BroadcastReceiver, context: Context) {
		val result = receiver.goAsync()
		CoroutineScope(Dispatchers.IO).launch {
			try {
				scheduleFromDisk(context)
			} catch (_: Exception) {
				// A folder w/o permission or a file gone: the next walk in the app schedules again.
			} finally {
				result.finish()
			}
		}
	}
}

// Posts an alarm, then sets the entry's next one.
class AlarmReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		Alarms.post(context, intent)
		Alarms.rescheduleAsync(this, context)
	}
}

// Sets the alarms again after a reboot, which clears them.
class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED) Alarms.rescheduleAsync(this, context)
	}
}
