package io.astrbot.android

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch

/** Keep the saved offset while initial system-bar insets temporarily change the scroll range. */
internal class PersonaScrollPosition(initial: Int = 0, restored: Boolean = false) {
    val state = ScrollState(initial)
    var restoreTarget: Int? = initial.takeIf { restored }
    var applyingRestore = false
    fun interacted() { restoreTarget = null }

    companion object {
        val Saver = Saver<PersonaScrollPosition, Int>(
            save = { it.restoreTarget ?: it.state.value },
            restore = { PersonaScrollPosition(it, restored = true) })
    }
}

@Composable internal fun rememberPersonaScrollPosition(): PersonaScrollPosition {
    val position = rememberSaveable(saver = PersonaScrollPosition.Saver) { PersonaScrollPosition() }
    LaunchedEffect(position) {
        launch {
            snapshotFlow { position.state.maxValue }.collect { maximum ->
                val target = position.restoreTarget
                if (target != null && maximum != Int.MAX_VALUE) {
                    position.applyingRestore = true
                    try { position.state.scrollTo(target.coerceAtMost(maximum)) }
                    finally { position.applyingRestore = false }
                }
            }
        }
        launch {
            position.state.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) position.interacted()
            }
        }
        launch {
            snapshotFlow { position.state.isScrollInProgress }.collect {
                if (it && !position.applyingRestore) position.interacted()
            }
        }
    }
    return position
}
