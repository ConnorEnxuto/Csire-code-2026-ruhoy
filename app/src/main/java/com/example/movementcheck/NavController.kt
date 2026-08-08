package com.example.movementcheck

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Focus-based navigation for the RayNeo X3 Pro touchpad.
 *
 * The glasses' temple touchpad emits swipes (move between elements) and taps (select),
 * per RayNeo's gesture model. Because the UI is drawn twice (one pane per eye), focus is
 * tracked here at the logical-control level rather than via Compose's per-view focus, and
 * the same highlighted control is rendered in both panes.
 *
 * The Compose screen rebuilds [items] every recomposition (the set of navigable controls
 * changes, e.g. when the CSV panel expands). [MainActivity.onKeyDown] maps touchpad
 * swipe/tap key events to [prev]/[next]/[activate].
 */
class NavController {
    /** A navigable control: a stable id (for focus highlight) and its activation action. */
    data class Item(val id: String, val onActivate: () -> Unit)

    var focused by mutableIntStateOf(0)
        private set

    /** Rebuilt by the Compose screen each recomposition; keeps [focused] in range. */
    var items: List<Item> = emptyList()
        set(value) {
            field = value
            if (focused > value.lastIndex) focused = value.lastIndex.coerceAtLeast(0)
        }

    /** Focus id currently highlighted, or null if nothing is navigable. */
    val focusedId: String? get() = items.getOrNull(focused)?.id

    fun next() { if (items.isNotEmpty()) focused = (focused + 1).coerceAtMost(items.lastIndex) }
    fun prev() { if (items.isNotEmpty()) focused = (focused - 1).coerceAtLeast(0) }
    fun activate() { items.getOrNull(focused)?.onActivate?.invoke() }

    /** Jump focus to a control by id (e.g. auto-focus the set marker in manual mode). */
    fun focusId(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) focused = i
    }
}
