package io.astrbot.android

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.astrbot.android.runtime.BackgroundAccessibilityService
import io.astrbot.android.runtime.BackgroundRecovery
import java.text.DateFormat
import java.util.Date

@Composable internal fun BackgroundAssistance() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val connected by BackgroundAccessibilityService.connected.collectAsStateWithLifecycle()
    val overlay by BackgroundAccessibilityService.overlayAttached.collectAsStateWithLifecycle()
    val recoveryRevision by BackgroundRecovery.changed.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    var explanation by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, settingsObserver)
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(settingsObserver)
        }
    }
    val accessibilityEnabled = remember(revision, connected) { BackgroundAccessibilityService.enabled(context) }
    val restoreEnabled = remember(revision, recoveryRevision) { BackgroundRecovery.enabled(context) }
    val lastAttempt = remember(revision, recoveryRevision) { BackgroundRecovery.lastAttempt(context) }

    fun openAccessibility() {
        if (BackgroundGuide.open(context, BackgroundAccessibilityService.settings(context)) < 0)
            Toast.makeText(context, AppText.get(R.string.ui_this_shortcut_is_unavailable_follow_the_steps_above_to), Toast.LENGTH_LONG).show()
    }
    ManagerSection(AppText.get(R.string.background_assistance_group)) {
        Row(Modifier.fillMaxWidth().toggleable(accessibilityEnabled || connected, role = Role.Switch, onValueChange = { enabled ->
            if (enabled || !BackgroundAccessibilityService.disable()) openAccessibility()
            revision++
        }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(AppText.get(R.string.background_assist_title), style = MaterialTheme.typography.bodyMedium)
                Text(AppText.get(when {
                    connected && overlay -> R.string.background_assist_connected
                    connected -> R.string.background_assist_partial
                    accessibilityEnabled -> R.string.background_assist_waiting
                    else -> R.string.background_assist_off
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = accessibilityEnabled || connected, onCheckedChange = null)
        }
        Text(AppText.get(R.string.background_assist_detail), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().toggleable(restoreEnabled, role = Role.Switch, onValueChange = { enabled ->
            if (!BackgroundRecovery.setEnabled(context, enabled))
                Toast.makeText(context, AppText.get(R.string.background_restore_save_failed), Toast.LENGTH_LONG).show()
        }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(AppText.get(R.string.background_restore_title), style = MaterialTheme.typography.bodyMedium)
                Text(AppText.get(R.string.background_restore_detail), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = restoreEnabled, onCheckedChange = null)
        }
        Row(Modifier.fillMaxWidth().clickable { explanation = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_info), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(AppText.get(R.string.ui_setup_recommendations_and_runtime_limitations), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        lastAttempt?.let { (time, accepted) ->
            Text(AppText.get(if (accepted) R.string.background_restore_requested else R.string.background_restore_blocked,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text(AppText.get(R.string.background_assistance_group)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppText.get(R.string.background_assist_description))
            Text(AppText.get(R.string.background_restore_scope))
            Text(AppText.get(R.string.background_restore_limit))
        } }, confirmButton = { TextButton(onClick = { explanation = false }) { Text(AppText.get(R.string.ui_close)) } },
        dismissButton = { TextButton(onClick = { explanation = false; openAccessibility() }) { Text(AppText.get(R.string.background_assist_settings)) } })
}
