// SPDX-License-Identifier: MIT
// Copyright (c) 2026 JunWan666
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.ManagerState
import io.astrbot.android.runtime.InstalledVersionInspection

@Composable internal fun ProjectAboutDialog(state: ManagerState, onDismiss: () -> Unit, onAuthor: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.get(R.string.ui_app_and_component_versions)) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManagerAppLogo(Modifier.size(48.dp))
                Column {
                    Text(ProjectIdentity.NAME, style = MaterialTheme.typography.titleMedium)
                    Text(AppText.get(R.string.ui_version_18ab8, BuildConfig.VERSION_NAME))
                }
            }
            Text(AppText.get(R.string.ui_developed_and_maintained_by, ProjectIdentity.AUTHOR))
            Text(AppText.get(R.string.ui_project_license, ProjectIdentity.LICENSE))
            Text(ProjectIdentity.COPYRIGHT, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAuthor) { Text(AppText.get(R.string.ui_author_s_github_profile)) }
            HorizontalDivider()
            state.versions.filterKeys { it != "App" }.forEach { (component, version) ->
                Text("${componentLabel(component)}: $version", style = MaterialTheme.typography.bodySmall)
            }
            state.versionNotice?.let {
                Text(it, color = if (it == InstalledVersionInspection.MATCHED_NOTICE)
                    MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            Text(AppText.get(R.string.ui_astrbot_and_napcat_support_independent_online_updates_retaining_configur),
                style = MaterialTheme.typography.bodySmall)
            Text(AppText.get(R.string.ui_third_party_projects_retain_their_authorship_licenses_and_trademarks), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_ok)) } })
}
