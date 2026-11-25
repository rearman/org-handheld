package com.rearman.orgpalm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearman.orgpalm.org.OrgFile
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

// The opacity of a Month cell outside the month in view.
private const val DIM = 0.45f

// The opacity of the primary color that tints today's column in Week & today's cell in Month.
private const val TODAY_TINT = 0.12f

// The most height that the band of untimed rows atop the Day & Week grids takes.
private val BAND = 140.dp

// The height of a span's lane in the Week band & in a Month week, & of a Month cell's date & its padding.
private val SPAN_WEEK = 20.dp
private val SPAN_MONTH = 14.dp
private val MONTH_DATE = 18.dp
private val MONTH_PAD = 2.dp

// The height of one hour in the Day & Week grids, & the hour that a grid opens at.
private val HOUR = 48.dp
private const val FIRST_HOUR = 7

// A timed entry w/o an end takes this many minutes of the grid.
private const val POINT_MINUTES = 30

private val TITLE_DAY = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")
private val TITLE_SHORT = DateTimeFormatter.ofPattern("MMM d")
private val TITLE_MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY_SHORT = DateTimeFormatter.ofPattern("EEE d")

// The bar that steps the date, which the top bar holds: the arrows close about the title, & Today
// after them, shown only while today is out of view.
@Composable
fun DateBar(level: Level, focus: LocalDate, onFocus: (LocalDate) -> Unit) {
	val today = LocalDate.now()
	Row(verticalAlignment = Alignment.CenterVertically) {
		Arrow("<") { onFocus(step(level, focus, -1)) }
		Text(
			title(level, focus),
			Modifier.weight(1f, fill = false),
			style = MaterialTheme.typography.titleMedium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Arrow(">") { onFocus(step(level, focus, 1)) }
		if (!shows(level, focus, today)) TextButton(onClick = { onFocus(today) }) { Text("Today") }
	}
}

@Composable
private fun Arrow(text: String, onClick: () -> Unit) {
	Text(
		text,
		Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
		style = MaterialTheme.typography.titleMedium,
		color = MaterialTheme.colorScheme.primary,
	)
}

// Whether the level at [focus] shows [today].
private fun shows(level: Level, focus: LocalDate, today: LocalDate): Boolean = when (level) {
	Level.AGENDA -> !today.isBefore(focus) && today.isBefore(focus.plusDays(Agenda.SPAN))
	Level.DAY -> today == focus
	Level.WEEK -> weekOf(today) == weekOf(focus)
	Level.MONTH -> today.withDayOfMonth(1) == focus.withDayOfMonth(1)
}

// The timeline: the level itself; the date in view ([focus]) is kept across a change of level.
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
// deadlines, then one hour grid for all the days. Day's band holds rows as the Agenda's; Week's, the
// spans across their days & a list per day.
@Composable
private fun CalendarGrid(days: List<AgendaDay>, today: LocalDate, showDayNames: Boolean, onOpen: (AgendaRow) -> Unit, onShow: (AgendaRow) -> Unit) {
	val gutter = 44.dp
	val line = MaterialTheme.colorScheme.outlineVariant
	Column(Modifier.fillMaxSize()) {
		if (showDayNames) {
			Row(Modifier.fillMaxWidth().padding(start = gutter)) {
				for (day in days) {
					Box(Modifier.weight(1f)) {
						TodayLabel(DAY_SHORT.format(day.date), day.date == today, MaterialTheme.colorScheme.onSurface)
					}
				}
			}
		}
		if (showDayNames) {
			WeekBand(days, today, gutter, onOpen, onShow)
		} else {
			Column(Modifier.fillMaxWidth().heightIn(max = BAND).verticalScroll(rememberCenteredScrollState())) {
				for (row in days.single().rows.filter { untimed(it) }) AgendaLine(row, onOpen, onShow)
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
					Modifier.weight(1f).height(HOUR * 24).background(todayTint(showDayNames && day.date == today)).drawBehind {
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
								(row.leaders + row.title).joinToString(" "),
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

// Whether a row goes in the band: w/o a time, & not a coming deadline's early warning.
private fun untimed(row: AgendaRow): Boolean = row.time == null && !row.early

// The Week band, no taller than BAND: the spans atop, each a bar over its days, then a list per day of
// its other untimed rows, each scrolled on its own. Limit: the spans' lanes do not scroll, so many
// spans at once crowd out the lists.
@Composable
private fun WeekBand(days: List<AgendaDay>, today: LocalDate, gutter: Dp, onOpen: (AgendaRow) -> Unit, onShow: (AgendaRow) -> Unit) {
	val bars = spans(days)
	val lanes = spanLanes(bars)
	val count = (lanes.maxOrNull() ?: -1) + 1
	Column(Modifier.fillMaxWidth().heightIn(max = BAND).padding(start = gutter)) {
		if (count > 0) {
			BoxWithConstraints(Modifier.fillMaxWidth().height(SPAN_WEEK * count)) {
				val col = maxWidth / days.size
				bars.forEachIndexed { i, s ->
					SpanBar(
						s.row,
						Modifier.offset(x = col * s.first, y = SPAN_WEEK * lanes[i]).width(col * (s.last - s.first + 1)).height(SPAN_WEEK),
						Modifier.combinedClickable(onLongClick = { onShow(s.row) }) { onOpen(s.row) },
						MaterialTheme.typography.labelSmall.fontSize,
					)
				}
			}
		}
		Row(Modifier.fillMaxWidth().weight(1f, fill = false)) {
			for (day in days) {
				Column(Modifier.weight(1f).background(todayTint(day.date == today)).verticalScroll(rememberCenteredScrollState()).padding(1.dp)) {
					for (row in day.rows.filter { untimed(it) && !inSpan(it, day.date) }) Untimed(row, onOpen, onShow)
				}
			}
		}
	}
}

// A range of more than one day drawn as one bar over a stretch of days, from day [first] to day
// [last] of the stretch.
private class Span(val row: AgendaRow, val first: Int, val last: Int)

// Whether [row] shows on [d] through a range of more than one day.
private fun inSpan(row: AgendaRow, d: LocalDate): Boolean = row.entry.ranges.any { it.start != it.endInclusive && d in it }

// The spans of a stretch of consecutive days: each range of more than one day that a row of the
// stretch shows, cut to the stretch, once, in the order of its first day.
private fun spans(days: List<AgendaDay>): List<Span> {
	val seen = HashSet<Triple<String, Int, ClosedRange<LocalDate>>>()
	val out = ArrayList<Span>()
	days.forEachIndexed { i, day ->
		for (row in day.rows) {
			for (r in row.entry.ranges) {
				if (r.start == r.endInclusive || day.date !in r) continue
				if (!seen.add(Triple(row.file.path, row.entry.anchor.lineIndex, r))) continue
				out += Span(row, i, minOf(days.lastIndex, i + ChronoUnit.DAYS.between(day.date, r.endInclusive).toInt()))
			}
		}
	}
	return out
}

// A lane per span, so that spans whose days overlap sit one above the other: each takes the first
// lane free at its first day.
private fun spanLanes(spans: List<Span>): List<Int> {
	val ends = ArrayList<Int>()
	return spans.map { s ->
		val lane = ends.indexOfFirst { it < s.first }.takeIf { it >= 0 } ?: ends.size.also { ends.add(0) }
		ends[lane] = s.last
		lane
	}
}

// A span's bar: the state & title on one line, on the untimed rows' color; [place] sets it over its
// days, & [act] (after the clip, for the ripple's shape) takes its taps.
@Composable
private fun SpanBar(row: AgendaRow, place: Modifier, act: Modifier, fontSize: TextUnit) {
	Text(
		listOfNotNull(row.entry.state, row.title).joinToString(" "),
		place
			.padding(1.dp)
			.clip(RoundedCornerShape(4.dp))
			.then(act)
			.background(MaterialTheme.colorScheme.secondaryContainer)
			.padding(horizontal = 4.dp),
		fontSize = fontSize,
		color = MaterialTheme.colorScheme.onSecondaryContainer,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
	)
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
		(row.leaders + listOfNotNull(row.entry.state, row.title)).joinToString(" "),
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

// A day's label: today's in bold on a filled pill of the primary color, any other in [color].
@Composable
private fun TodayLabel(text: String, isToday: Boolean, color: Color) {
	Text(
		text,
		if (isToday) Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp) else Modifier,
		style = MaterialTheme.typography.labelMedium,
		fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
		color = if (isToday) MaterialTheme.colorScheme.onPrimary else color,
	)
}

// The tint behind today's column or cell, & none behind any other.
@Composable
private fun todayTint(isToday: Boolean): Color =
	if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = TODAY_TINT) else Color.Transparent

// The Month grid: a row per week, a cell per day w/ its date & the titles that fit, & the week's spans
// as bars over their days below the dates; a tap on a day calls [onDay]. The days of the months
// before & after are dimmed.
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
			val bars = spans(week)
			val lanes = spanLanes(bars)
			val count = (lanes.maxOrNull() ?: -1) + 1
			BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
				Row(Modifier.fillMaxSize()) {
					for (day in week) {
						val inMonth = day.date.monthValue == month
						Column(
							Modifier
								.weight(1f)
								.fillMaxHeight()
								.drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f)) }
								.background(if (inMonth) todayTint(day.date == today) else MaterialTheme.colorScheme.surfaceVariant)
								.alpha(if (inMonth) 1f else DIM)
								.clickable { onDay(day.date) }
								.padding(MONTH_PAD),
						) {
							Box(Modifier.height(MONTH_DATE)) {
								TodayLabel(
									day.date.dayOfMonth.toString(),
									day.date == today,
									if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							// The spans' bars take the lanes below the date; the other rows fill the rest of three lines.
							Spacer(Modifier.height(SPAN_MONTH * count))
							val others = day.rows.filterNot { inSpan(it, day.date) }
							val room = maxOf(0, 3 - count)
							for (row in others.take(room)) {
								Text(row.title, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
							}
							if (others.size > room) {
								Text("+${others.size - room}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
							}
						}
					}
				}
				// The bars take no taps, so that a tap on one opens its day as a tap on the cell does.
				val col = maxWidth / 7
				bars.forEachIndexed { i, s ->
					SpanBar(
						s.row,
						Modifier
							.offset(x = col * s.first, y = MONTH_PAD + MONTH_DATE + SPAN_MONTH * lanes[i])
							.width(col * (s.last - s.first + 1))
							.height(SPAN_MONTH),
						Modifier,
						10.sp,
					)
				}
			}
		}
	}
}
