package com.rearman.orgpalm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearman.orgpalm.org.OrgFile
import java.time.DayOfWeek
import java.time.LocalDate

import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

// The four zoom levels, from the closest to the widest.
enum class Level(val label: String) {
	AGENDA("Agenda"),
	DAY("Day"),
	WEEK("Week"),
	MONTH("Month"),
}

// Weeks start on Sunday, as calendar-week-start-day 0 (Emacs's default, which init.el keeps) starts them.
private val WEEK_START = DayOfWeek.SUNDAY

private fun weekOf(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(WEEK_START))

// The pinch that steps a level: a scale below PINCH_IN (fingers together) steps toward Month, one above
// PINCH_OUT (fingers apart) toward Agenda, once per gesture.
private const val PINCH_IN = 0.75f
private const val PINCH_OUT = 1.33f

// The width that a one-finger swipe must cross to step the date in Day, Week, & Month.
private val SWIPE = 64.dp

// The height of one hour in the Day & Week grids, & the hour that a grid opens at.
private val HOUR = 48.dp
private const val FIRST_HOUR = 7

// A timed entry w/o an end takes this many minutes of the grid.
private const val POINT_MINUTES = 30

private val TITLE_DAY = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")
private val TITLE_SHORT = DateTimeFormatter.ofPattern("MMM d")
private val TITLE_MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY_SHORT = DateTimeFormatter.ofPattern("EEE d")

// The chips that pick the level, which the top bar holds; they scroll sideways where the bar is narrow.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelChips(level: Level, onLevel: (Level) -> Unit) {
	Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		for (l in Level.entries) FilterChip(selected = l == level, onClick = { onLevel(l) }, label = { Text(l.label) })
	}
}

// The timeline: the bar that steps the date, & the level itself. The date in view ([focus]) is kept
// across a change of level.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Timeline(
	files: List<OrgFile>,
	level: Level,
	onLevel: (Level) -> Unit,
	focus: LocalDate,
	onFocus: (LocalDate) -> Unit,
	onOpen: (AgendaRow) -> Unit,
	onShow: (AgendaRow) -> Unit,
) {
	val today = LocalDate.now()
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
			TextButton(onClick = { onFocus(step(level, focus, -1)) }) { Text("<") }
			Text(title(level, focus), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
			TextButton(onClick = { onFocus(today) }) { Text("Today") }
			TextButton(onClick = { onFocus(step(level, focus, 1)) }) { Text(">") }
		}
		val levels = Level.entries
		Box(
			Modifier.weight(1f).fillMaxWidth().pinch(level, {
				if (level.ordinal < levels.size - 1) onLevel(levels[level.ordinal + 1])
			}, {
				if (level.ordinal > 0) onLevel(levels[level.ordinal - 1])
			}).then(
				if (level == Level.AGENDA) Modifier
				else Modifier.swipe(level to focus, { onFocus(step(level, focus, -1)) }, { onFocus(step(level, focus, 1)) }),
			),
		) {
			when (level) {
				Level.AGENDA -> AgendaList(remember(files, focus, today) { Agenda.of(files, focus, today) }, onOpen, onShow)
				Level.DAY -> {
					val days = remember(files, focus, today) { Agenda.days(files, focus, focus, today) }
					CalendarGrid(days, today, showDayNames = false, onOpen, onShow)
				}
				Level.WEEK -> {
					val start = weekOf(focus)
					val days = remember(files, start, today) { Agenda.days(files, start, start.plusDays(6), today) }
					CalendarGrid(days, today, showDayNames = true, onOpen, onShow)
				}
				Level.MONTH -> {
					val first = focus.withDayOfMonth(1)
					val start = weekOf(first)
					val end = weekOf(first.plusMonths(1).minusDays(1)).plusDays(6)
					val days = remember(files, start, end, today) { Agenda.days(files, start, end, today) }
					MonthGrid(days, first.month.value, today) { onFocus(it); onLevel(Level.DAY) }
				}
			}
		}
	}
}

private fun step(level: Level, d: LocalDate, sign: Long): LocalDate = when (level) {
	Level.AGENDA -> d.plusDays(Agenda.SPAN * sign)
	Level.DAY -> d.plusDays(sign)
	Level.WEEK -> d.plusWeeks(sign)
	Level.MONTH -> d.plusMonths(sign)
}

private fun title(level: Level, d: LocalDate): String = when (level) {
	Level.AGENDA -> "${TITLE_SHORT.format(d)} to ${TITLE_SHORT.format(d.plusDays(Agenda.SPAN - 1))}"
	Level.DAY -> TITLE_DAY.format(d)
	Level.WEEK -> weekOf(d).let { "${TITLE_SHORT.format(it)} to ${TITLE_SHORT.format(it.plusDays(6))}, ${it.plusDays(6).year}" }
	Level.MONTH -> TITLE_MONTH.format(d)
}

// Two fingers that close past PINCH_IN call [closer], or open past PINCH_OUT call [wider]. The
// events are seen ere the children see them, & a two-finger move is consumed, so a list or grid
// below does not scroll w/ it; one finger passes through.
private fun Modifier.pinch(key: Any, closer: () -> Unit, wider: () -> Unit): Modifier = pointerInput(key) {
	awaitEachGesture {
		var scale = 1f
		var fired = false
		do {
			val event = awaitPointerEvent(PointerEventPass.Initial)
			if (event.changes.count { it.pressed } >= 2) {
				scale *= event.calculateZoom()
				event.changes.forEach { it.consume() }
				if (!fired && scale < PINCH_IN) {
					fired = true
					closer()
				} else if (!fired && scale > PINCH_OUT) {
					fired = true
					wider()
				}
			}
		} while (event.changes.any { it.pressed })
	}
}

// One finger that ends a horizontal drag more than SWIPE to the right calls [back], or to the left
// calls [ahead]. The children see the events first, so a vertical scroll below keeps its own drags,
// & a pinch, which consumes the events, cancels the drag.
private fun Modifier.swipe(key: Any, back: () -> Unit, ahead: () -> Unit): Modifier = pointerInput(key) {
	var dx = 0f
	detectHorizontalDragGestures(
		onDragStart = { dx = 0f },
		onDragEnd = {
			if (dx > SWIPE.toPx()) back() else if (dx < -SWIPE.toPx()) ahead()
		},
	) { change, amount ->
		change.consume()
		dx += amount
	}
}

// The Day & Week grids: a band of untimed rows atop each day, w/o the early warnings of coming
// deadlines, then one hour grid for all the days.
@Composable
private fun CalendarGrid(days: List<AgendaDay>, today: LocalDate, showDayNames: Boolean, onOpen: (AgendaRow) -> Unit, onShow: (AgendaRow) -> Unit) {
	val gutter = 44.dp
	val line = MaterialTheme.colorScheme.outlineVariant
	Column(Modifier.fillMaxSize()) {
		if (showDayNames) {
			Row(Modifier.fillMaxWidth().padding(start = gutter)) {
				for (day in days) {
					Text(
						DAY_SHORT.format(day.date),
						Modifier.weight(1f),
						style = MaterialTheme.typography.labelMedium,
						fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal,
						color = if (day.date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					)
				}
			}
		}
		Row(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberCenteredScrollState()).padding(start = gutter)) {
			for (day in days) {
				Column(Modifier.weight(1f).padding(1.dp)) {
					for (row in day.rows.filter { it.time == null && !it.early }) Untimed(row, onOpen, onShow)
				}
			}
		}
		HorizontalDivider()
		val start = with(LocalDensity.current) { (HOUR * FIRST_HOUR).roundToPx() }
		Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberCenteredScrollState(start))) {
			Column(Modifier.width(gutter)) {
				for (h in 0 until 24) {
					Text("%02d:00".format(h), Modifier.height(HOUR).padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
				}
			}
			for (day in days) {
				BoxWithConstraints(
					Modifier.weight(1f).height(HOUR * 24).drawBehind {
						for (h in 0 until 24) {
							val y = HOUR.toPx() * h
							drawLine(line, Offset(0f, y), Offset(size.width, y))
						}
						drawLine(line, Offset(0f, 0f), Offset(0f, size.height))
					},
				) {
					val timed = day.rows.filter { it.time != null }
					val lanes = lanes(timed)
					val count = (lanes.maxOrNull() ?: 0) + 1
					timed.forEachIndexed { i, row ->
						val from = row.time!!.toSecondOfDay() / 60
						val to = minutesEnd(row)
						Box(
							Modifier
								.offset(x = maxWidth * lanes[i] / count, y = HOUR * from / 60)
								.width(maxWidth / count)
								.height(HOUR * (to - from) / 60)
								.padding(1.dp)
								.clip(RoundedCornerShape(4.dp))
								.combinedClickable(onLongClick = { onShow(row) }) { onOpen(row) }
								.background(MaterialTheme.colorScheme.primaryContainer)
								.padding(2.dp),
						) {
							Text(
								(row.leaders + row.entry.title).joinToString(" "),
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onPrimaryContainer,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}
			}
		}
	}
}

// The minute of the day at which a timed row ends: its end, else POINT_MINUTES after its start, at
// most midnight.
private fun minutesEnd(row: AgendaRow): Int {
	val from = row.time!!.toSecondOfDay() / 60
	val end = row.end?.takeIf { it.isAfter(row.time) }?.let { it.toSecondOfDay() / 60 } ?: (from + POINT_MINUTES)
	return minOf(end, 24 * 60)
}

// A lane per timed row, so that rows whose times overlap sit side by side: each row takes the first
// lane free at its start. Limit: every row of the day shares the width of the most lanes the day
// needs; a lane count per cluster of overlapping rows is the upgrade.
private fun lanes(rows: List<AgendaRow>): List<Int> {
	val ends = ArrayList<Int>()
	return rows.map { row ->
		val from = row.time!!.toSecondOfDay() / 60
		val lane = ends.indexOfFirst { it <= from }.takeIf { it >= 0 } ?: ends.size.also { ends.add(0) }
		ends[lane] = minutesEnd(row)
		lane
	}
}

@Composable
private fun Untimed(row: AgendaRow, onOpen: (AgendaRow) -> Unit, onShow: (AgendaRow) -> Unit) {
	Text(
		(row.leaders + listOfNotNull(row.entry.state, row.entry.title)).joinToString(" "),
		Modifier
			.fillMaxWidth()
			.padding(vertical = 1.dp)
			.clip(RoundedCornerShape(4.dp))
			.combinedClickable(onLongClick = { onShow(row) }) { onOpen(row) }
			.background(MaterialTheme.colorScheme.secondaryContainer)
			.padding(horizontal = 4.dp, vertical = 2.dp),
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSecondaryContainer,
		maxLines = 2,
		overflow = TextOverflow.Ellipsis,
	)
}

// The Month grid: a row per week, a cell per day w/ its date & the titles that fit; a tap on a day
// calls [onDay].
@Composable
private fun MonthGrid(days: List<AgendaDay>, month: Int, today: LocalDate, onDay: (LocalDate) -> Unit) {
	val line = MaterialTheme.colorScheme.outlineVariant
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth()) {
			for (day in days.take(7)) {
				Text(
					day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
					Modifier.weight(1f).padding(4.dp),
					style = MaterialTheme.typography.labelMedium,
				)
			}
		}
		for (week in days.chunked(7)) {
			Row(Modifier.fillMaxWidth().weight(1f)) {
				for (day in week) {
					val inMonth = day.date.monthValue == month
					Column(
						Modifier
							.weight(1f)
							.fillMaxHeight()
							.drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f)) }
							.clickable { onDay(day.date) }
							.padding(2.dp),
					) {
						Text(
							day.date.dayOfMonth.toString(),
							style = MaterialTheme.typography.labelMedium,
							fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal,
							color = when {
								day.date == today -> MaterialTheme.colorScheme.primary
								inMonth -> MaterialTheme.colorScheme.onSurface
								else -> MaterialTheme.colorScheme.onSurfaceVariant
							},
						)
						for (row in day.rows.take(3)) {
							Text(row.entry.title, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
						}
						if (day.rows.size > 3) {
							Text("+${day.rows.size - 3}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					}
				}
			}
		}
	}
}
