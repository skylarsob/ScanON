@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.skyler.scanon.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyler.scanon.BuildConfig
import com.skyler.scanon.R
import com.skyler.scanon.data.ThemeMode
import com.skyler.scanon.engine.GeoDb
import com.skyler.scanon.ui.components.ScanButton
import com.skyler.scanon.ui.components.GlassCard
import com.skyler.scanon.ui.components.LocalSnack
import com.skyler.scanon.ui.components.SectionLabel
import com.skyler.scanon.ui.vm.SettingsViewModel

@Composable
fun SettingsScreen(onLanguageChanged: (String) -> Unit, vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val geo by GeoDb.state.collectAsState()
    LaunchedEffect(vm) { vm.events.collect { snack(it) } }
    val backupOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? -> uri?.let { vm.writeBackup(it) } }
    val backupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { vm.restoreBackup(it) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GlassCard {
                SectionLabel(stringResource(R.string.appearance))
                Text(stringResource(R.string.appearance_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        com.skyler.scanon.data.AppearancePreset.CLASSIC to stringResource(R.string.preset_classic),
                        com.skyler.scanon.data.AppearancePreset.OBSIDIAN to stringResource(R.string.preset_obsidian),
                        com.skyler.scanon.data.AppearancePreset.TOKYO to stringResource(R.string.preset_tokyo),
                        com.skyler.scanon.data.AppearancePreset.MATRIX to stringResource(R.string.preset_matrix),
                        com.skyler.scanon.data.AppearancePreset.FROSTED to stringResource(R.string.preset_frosted),
                    ).forEach { (preset, label) ->
                        FilterChip(s.preset == preset, { vm.update { it.copy(preset = preset) } }, label = { Text(label) })
                    }
                }
                if (s.preset != com.skyler.scanon.data.AppearancePreset.CLASSIC) {
                    Text(stringResource(R.string.theme_classic_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionLabel(stringResource(R.string.accent))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Preset default
                    Box(
                        Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                if (s.accentArgb == 0) 2.dp else 1.dp,
                                if (s.accentArgb == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                androidx.compose.foundation.shape.CircleShape)
                            .clickable { vm.update { it.copy(accentArgb = 0) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary))
                    }
                    com.skyler.scanon.ui.theme.AccentSwatches.forEach { c ->
                        val argb = c.toArgb()
                        Box(
                            Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(c)
                                .border(
                                    if (s.accentArgb == argb) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    androidx.compose.foundation.shape.CircleShape)
                                .clickable { vm.update { it.copy(accentArgb = argb) } })
                    }
                }
                Text(
                    if (s.accentArgb == 0) stringResource(R.string.accent_default)
                    else "#" + s.accentArgb.toUInt().toString(16).uppercase().padStart(8, '0'),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionLabel(stringResource(R.string.glass))
                androidx.compose.material3.Slider(
                    value = s.glass, onValueChange = { v -> vm.update { it.copy(glass = v) } },
                    valueRange = 0f..1f)
                Text(stringResource(R.string.glass_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionLabel(stringResource(R.string.density))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        com.skyler.scanon.data.DensityMode.COMPACT to stringResource(R.string.density_compact),
                        com.skyler.scanon.data.DensityMode.COMFORTABLE to stringResource(R.string.density_comfortable),
                        com.skyler.scanon.data.DensityMode.SPACIOUS to stringResource(R.string.density_spacious),
                    ).forEach { (mode, label) ->
                        FilterChip(s.densityMode == mode, { vm.update { it.copy(densityMode = mode) } }, label = { Text(label) })
                    }
                }
                SectionLabel(stringResource(R.string.radius))
                androidx.compose.material3.Slider(
                    value = s.radiusScale, onValueChange = { v -> vm.update { it.copy(radiusScale = v) } },
                    valueRange = 0.5f..1.5f)
            }
        }
        item {
            GlassCard {
                SectionLabel(stringResource(R.string.language))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system" to stringResource(R.string.system_default), "en" to "English", "fa" to "فارسی").forEach { (code, label) ->
                        FilterChip(s.lang == code, {
                            if (s.lang != code) { vm.update { it.copy(lang = code) }; onLanguageChanged(code) }
                        }, label = { Text(label) })
                    }
                }
                SectionLabel(stringResource(R.string.theme))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ThemeMode.SYSTEM to stringResource(R.string.system_default), ThemeMode.DARK to stringResource(R.string.dark),
                        ThemeMode.LIGHT to stringResource(R.string.light)).forEach { (m, label) ->
                        FilterChip(s.theme == m, { vm.update { it.copy(theme = m) } }, label = { Text(label) })
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel(stringResource(R.string.behaviour))
                SwitchRow(stringResource(R.string.reduce_motion), stringResource(R.string.reduce_motion_desc), s.reduceMotion) { v -> vm.update { it.copy(reduceMotion = v) } }
                SwitchRow(stringResource(R.string.haptics), null, s.haptics) { v -> vm.update { it.copy(haptics = v) } }
                SwitchRow(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_desc), s.keepScreenOn) { v -> vm.update { it.copy(keepScreenOn = v) } }
                SwitchRow(stringResource(R.string.notify_finish), stringResource(R.string.notify_finish_desc), s.notifyOnFinish) { v -> vm.update { it.copy(notifyOnFinish = v) } }
                SwitchRow(stringResource(R.string.compact), stringResource(R.string.compact_desc), s.compact) { v -> vm.update { it.copy(compact = v) } }
            }
        }
        item {
            GlassCard {
                SectionLabel(stringResource(R.string.backup_title))
                Text(stringResource(R.string.backup_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanButton(onClick = { backupOut.launch("scanon-backup.json") }) { Text(stringResource(R.string.backup_export)) }
                    ScanButton(onClick = { backupIn.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text(stringResource(R.string.backup_import)) }
                }
            }
        }
        item {
            GlassCard {
                SectionLabel(stringResource(R.string.about_title))
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall)
                Text("ScanON ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelMedium)
                Text(
                    when (geo) {
                        GeoDb.State.READY -> stringResource(R.string.geo_ready)
                        GeoDb.State.PARTIAL -> stringResource(R.string.geo_partial, GeoDb.error ?: "")
                        GeoDb.State.FAILED -> stringResource(R.string.geo_failed, GeoDb.error ?: "")
                        GeoDb.State.LOADING -> stringResource(R.string.geo_not_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (geo == GeoDb.State.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                ScanButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.maxmind.com"))) } catch (_: Exception) {}
                }) { Text("MaxMind GeoLite2") }
                ScanButton(onClick = { vm.update { it.copy(onboarded = false) } }) { Text(stringResource(R.string.ob_again)) }
            }
        }
    }
}

@Composable
fun GuideScreen() {
    val ids = listOf(
        R.string.guide_t19 to R.string.guide_b19,
        R.string.guide_t16 to R.string.guide_b16,
        R.string.guide_t17 to R.string.guide_b17,
        R.string.guide_t18 to R.string.guide_b18,
        R.string.guide_t12 to R.string.guide_b12,
        R.string.guide_t13 to R.string.guide_b13,
        R.string.guide_t14 to R.string.guide_b14,
        R.string.guide_t15 to R.string.guide_b15,
        R.string.guide_t0 to R.string.guide_b0, R.string.guide_t1 to R.string.guide_b1, R.string.guide_t2 to R.string.guide_b2,
        R.string.guide_t3 to R.string.guide_b3, R.string.guide_t4 to R.string.guide_b4, R.string.guide_t5 to R.string.guide_b5,
        R.string.guide_t6 to R.string.guide_b6, R.string.guide_t7 to R.string.guide_b7, R.string.guide_t8 to R.string.guide_b8,
        R.string.guide_t9 to R.string.guide_b9, R.string.guide_t10 to R.string.guide_b10, R.string.guide_t11 to R.string.guide_b11,
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(ids.size) { i ->
            GlassCard {
                SectionLabel(stringResource(ids[i].first))
                Text(stringResource(ids[i].second), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
