package com.rearman.orgpalm

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

// A list's state that keeps the item at the center of the list across a rotation. [initial], the index
// of an item, is centered when the list is first shown.
@Composable
fun rememberCenteredListState(initial: Int? = null): LazyListState {
	val state = rememberLazyListState()
	var center by rememberSaveable { mutableStateOf(initial) }
	LaunchedEffect(state) {
		center?.let { i ->
			// The list must be laid out once ere an item's size is known.
			snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
			state.scrollToItem(i)
			val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
			if (item != null) {
				val mid = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
				state.scrollBy((item.offset + item.size / 2 - mid).toFloat())
			}
		}
		snapshotFlow {
			val info = state.layoutInfo
			val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
			info.visibleItemsInfo.firstOrNull { it.offset <= mid && mid < it.offset + it.size }?.index
		}.collect { if (it != null) center = it }
	}
	return state
}

// A scroll's state that keeps the point at the center of its viewport across a rotation; it opens w/
// [initialTop] at the top.
@Composable
fun rememberCenteredScrollState(initialTop: Int = 0): ScrollState {
	val state = rememberScrollState(initialTop)
	var center by rememberSaveable { mutableStateOf<Int?>(null) }
	LaunchedEffect(state) {
		center?.let { c ->
			snapshotFlow { state.viewportSize }.first { it > 0 }
			state.scrollTo(c - state.viewportSize / 2)
		}
		snapshotFlow { if (state.viewportSize > 0) state.value + state.viewportSize / 2 else null }
			.collect { if (it != null) center = it }
	}
	return state
}
