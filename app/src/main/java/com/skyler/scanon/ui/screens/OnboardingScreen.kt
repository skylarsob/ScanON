@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.skyler.scanon.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyler.scanon.R
import com.skyler.scanon.data.AppearancePreset
import com.skyler.scanon.data.DensityMode
import com.skyler.scanon.data.ThemeMode
import com.skyler.scanon.ui.components.PrimaryButton
import com.skyler.scanon.ui.components.ScanButton
import com.skyler.scanon.ui.components.GlassCard
import com.skyler.scanon.ui.components.SectionLabel
import com.skyler.scanon.ui.components.SpinningRadar
import com.skyler.scanon.ui.theme.AccentSwatches
import com.skyler.scanon.ui.vm.SettingsViewModel

/**
 * First-run introduction (2.8): shown exactly once, until it is finished or
 * skipped (Settings → About can bring it back). Language applies the moment
 * it is tapped (2.8.1 fix — in 2.8 it only applied on Finish, so the intro
 * stayed in the old language): the activity restarts for the new locale and
 * the page survives because it is rememberSaveable.
 */
@Composable
fun OnboardingScreen(onLanguageChanged: (String) -> Unit, vm: SettingsViewModel = viewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var lang by rememberSaveable { mutableStateOf(s.lang) }

    fun finish() {
        val chosen = lang
        vm.update { it.copy(lang = chosen, onboarded = true) }
        if (chosen != s.lang) onLanguageChanged(chosen)
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpinningRadar(MaterialTheme.colorScheme.primary, size = 30.dp, durationMillis = 9000)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // Page dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0..2) Box(
                    Modifier.size(if (i == page) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (fadeIn() + slideInHorizontally { dir * it / 3 }) togetherWith (fadeOut() + slideOutHorizontally { -dir * it / 3 })
            },
            label = "onboarding",
            modifier = Modifier.weight(1f),
        ) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (p) {
                    0 -> {
                        Text(stringResource(R.string.ob_welcome_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ob_welcome), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GlassCard {
                            SectionLabel(stringResource(R.string.language))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("system" to stringResource(R.string.system_default), "en" to "English", "fa" to "فارسی")
                                    .forEach { (code, label) ->
                                        FilterChip(lang == code, {
                                            if (lang != code) {
                                                lang = code
                                                vm.update { it.copy(lang = code) }
                                                onLanguageChanged(code)
                                            }
                                        }, label = { Text(label) })
                                    }
                            }
                            Text(stringResource(R.string.ob_lang_note), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    1 -> {
                        Text(stringResource(R.string.ob_appearance_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ob_appearance), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GlassCard {
                            SectionLabel(stringResource(R.string.appearance))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    AppearancePreset.CLASSIC to stringResource(R.string.preset_classic),
                                    AppearancePreset.OBSIDIAN to stringResource(R.string.preset_obsidian),
                                    AppearancePreset.TOKYO to stringResource(R.string.preset_tokyo),
                                    AppearancePreset.MATRIX to stringResource(R.string.preset_matrix),
                                    AppearancePreset.FROSTED to stringResource(R.string.preset_frosted),
                                ).forEach { (preset, label) ->
                                    FilterChip(s.preset == preset, { vm.update { it.copy(preset = preset) } }, label = { Text(label) })
                                }
                            }
                            if (s.preset == AppearancePreset.CLASSIC) {
                                SectionLabel(stringResource(R.string.theme))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(ThemeMode.SYSTEM to stringResource(R.string.system_default),
                                        ThemeMode.DARK to stringResource(R.string.dark),
                                        ThemeMode.LIGHT to stringResource(R.string.light)).forEach { (m, label) ->
                                        FilterChip(s.theme == m, { vm.update { it.copy(theme = m) } }, label = { Text(label) })
                                    }
                                }
                            }
                            SectionLabel(stringResource(R.string.accent))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(if (s.accentArgb == 0) 2.dp else 1.dp,
                                            if (s.accentArgb == 0) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), CircleShape)
                                        .clickable { vm.update { it.copy(accentArgb = 0) } },
                                    contentAlignment = Alignment.Center,
                                ) { Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
                                AccentSwatches.forEach { c ->
                                    val argb = c.toArgb()
                                    Box(Modifier.size(32.dp).clip(CircleShape).background(c)
                                        .border(if (s.accentArgb == argb) 2.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable { vm.update { it.copy(accentArgb = argb) } })
                                }
                            }
                            SectionLabel(stringResource(R.string.glass))
                            Slider(value = s.glass, onValueChange = { v -> vm.update { it.copy(glass = v) } }, valueRange = 0f..1f)
                            SectionLabel(stringResource(R.string.density))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(DensityMode.COMPACT to stringResource(R.string.density_compact),
                                    DensityMode.COMFORTABLE to stringResource(R.string.density_comfortable),
                                    DensityMode.SPACIOUS to stringResource(R.string.density_spacious)).forEach { (mode, label) ->
                                    FilterChip(s.densityMode == mode, { vm.update { it.copy(densityMode = mode) } }, label = { Text(label) })
                                }
                            }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.ob_behaviour_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ob_behaviour), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GlassCard {
                            SwitchRow(stringResource(R.string.reduce_motion), stringResource(R.string.reduce_motion_desc), s.reduceMotion) { v -> vm.update { it.copy(reduceMotion = v) } }
                            SwitchRow(stringResource(R.string.haptics), null, s.haptics) { v -> vm.update { it.copy(haptics = v) } }
                            SwitchRow(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_desc), s.keepScreenOn) { v -> vm.update { it.copy(keepScreenOn = v) } }
                            SwitchRow(stringResource(R.string.notify_finish), stringResource(R.string.notify_finish_desc), s.notifyOnFinish) { v -> vm.update { it.copy(notifyOnFinish = v) } }
                        }
                        GlassCard {
                            SectionLabel(stringResource(R.string.ob_bg_title))
                            Text(stringResource(R.string.ob_bg_note), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (page < 2) TextButton(onClick = { vm.update { it.copy(onboarded = true) } }) { Text(stringResource(R.string.ob_skip)) }
            Spacer(Modifier.weight(1f))
            if (page > 0) {
                ScanButton(onClick = { page-- }) { Text(stringResource(R.string.ob_back)) }
                Spacer(Modifier.width(8.dp))
            }
            if (page < 2) PrimaryButton(onClick = { page++ }) { Text(stringResource(R.string.ob_next)) }
            else PrimaryButton(onClick = { finish() }) { Text(stringResource(R.string.ob_finish)) }
        }
    }
}
