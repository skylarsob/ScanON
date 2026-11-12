@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.skyler.scanon.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyler.scanon.R
import com.skyler.scanon.engine.Fmt
import com.skyler.scanon.engine.Method
import com.skyler.scanon.engine.ScanConfig
import com.skyler.scanon.ui.components.PrimaryButton
import com.skyler.scanon.ui.components.ScanButton
import com.skyler.scanon.ui.components.AnimatedStatBox
import com.skyler.scanon.ui.components.AppIcon
import com.skyler.scanon.ui.components.AppIconKind
import com.skyler.scanon.ui.components.DropDown
import com.skyler.scanon.ui.components.entrance
import com.skyler.scanon.ui.components.EmptyState
import com.skyler.scanon.ui.components.ExportBar
import com.skyler.scanon.ui.components.Field
import com.skyler.scanon.ui.components.GlassCard
import com.skyler.scanon.ui.components.IconTextButton
import com.skyler.scanon.ui.components.LocalSnack
import com.skyler.scanon.ui.components.LtrText
import com.skyler.scanon.ui.components.ProgressCard
import com.skyler.scanon.ui.components.pressScale
import com.skyler.scanon.ui.components.SectionLabel
import com.skyler.scanon.ui.components.StatBox
import com.skyler.scanon.ui.components.rememberHaptics
import com.skyler.scanon.ui.theme.Sky
import com.skyler.scanon.ui.theme.Teal
import com.skyler.scanon.ui.vm.IpScanViewModel
import com.skyler.scanon.ui.vm.SortKey

@Composable
fun presetLabel(p: String): String = when (p) {
    "very_slow" -> stringResource(R.string.preset_very_slow)
    "slow" -> stringResource(R.string.preset_slow)
    "balanced" -> stringResource(R.string.preset_balanced)
    "fast" -> stringResource(R.string.preset_fast)
    "very_fast" -> stringResource(R.string.preset_very_fast)
    "iran" -> stringResource(R.string.preset_iran)
    "iran_fast" -> stringResource(R.string.preset_iran_fast)
    "iran_deep" -> stringResource(R.string.preset_iran_deep)
    "battery" -> stringResource(R.string.preset_battery)
    else -> p
}

@Composable
fun sortLabel(k: SortKey): String = when (k) {
    SortKey.SCORE -> stringResource(R.string.sort_score)
    SortKey.LATENCY -> stringResource(R.string.sort_latency)
    SortKey.JITTER -> stringResource(R.string.sort_jitter)
    SortKey.SUCCESS -> stringResource(R.string.sort_success)
    SortKey.LOSS -> stringResource(R.string.sort_loss)
    SortKey.IP -> stringResource(R.string.sort_ip)
    SortKey.SPEED -> stringResource(R.string.sort_speed)
}

@Composable
fun IpScanScreen(vm: IpScanViewModel = viewModel()) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val haptics = rememberHaptics()
    val form by vm.form.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val visible by vm.visible.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(vm) { vm.events.collect { snack(it) } }
    var wasRunning by rememberSaveable { mutableStateOf(running) }
    LaunchedEffect(running) { if (wasRunning && !running) haptics.success(); wasRunning = running }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.loadFile(it) } }
    val invalidMsg = stringResource(R.string.err_invalid_value)

    LazyColumn(
        Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "hero") {
            val heroText = if (MaterialTheme.colorScheme.primary.luminance() > 0.3f) Color(0xFF0A0F14) else Color.White
            // Living gradient (2.6): the two scheme colours slowly trade places.
            val reduceHero = com.skyler.scanon.ui.theme.LocalReduceMotion.current
            val shift = if (reduceHero) 0.25f else {
                val t = rememberInfiniteTransition("hero")
                val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5200), RepeatMode.Reverse), label = "heroShift")
                v
            }
            val scheme = MaterialTheme.colorScheme
            Column(
                Modifier.fillMaxWidth().entrance(0).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(
                        lerp(scheme.primary, scheme.secondary, shift * 0.55f).copy(alpha = 0.92f),
                        lerp(scheme.secondary, scheme.primary, shift * 0.55f).copy(alpha = 0.92f))))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.quick_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = heroText)
                Text(stringResource(R.string.quick_desc), style = MaterialTheme.typography.bodySmall, color = heroText)
                val heroBtnInteraction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                PrimaryButton(onClick = { vm.quickScan() }, enabled = !running,
                    interactionSource = heroBtnInteraction,
                    modifier = Modifier.pressScale(heroBtnInteraction)) {
                    AppIcon(AppIconKind.Play, MaterialTheme.colorScheme.onPrimary, size = 16.dp)
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.quick_btn))
                }
            }
        }

        item(key = "targets") {
            GlassCard(Modifier.entrance(1)) {
                SectionLabel(stringResource(R.string.targets_title))
                Field(stringResource(R.string.targets_hint), form.targets, { t -> vm.updateForm { it.copy(targets = t) } },
                    Modifier.fillMaxWidth(), singleLine = false, minLines = 3, maxLines = 8)
                preview?.let { p ->
                    LtrText(
                        stringResource(R.string.targets_preview, p.ips.size, p.hostnames.size, p.invalid) + if (p.truncated) " ⚠" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (p.invalid > 0 || p.truncated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Aligned 2x2 action grid (2.9.1): equal-width solid buttons in two
                // even rows, replacing the loose text links that sat unevenly.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconTextButton(stringResource(R.string.paste), AppIconKind.Copy, {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (text.isNotBlank()) vm.appendTargets(listOf(text))
                        }, modifier = Modifier.weight(1f))
                        IconTextButton(stringResource(R.string.load_file), AppIconKind.Upload, { picker.launch("text/*") }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconTextButton(stringResource(R.string.dedupe), AppIconKind.Filter, { vm.dedupeTargets() }, modifier = Modifier.weight(1f))
                        IconTextButton(stringResource(R.string.clear), AppIconKind.Close, { vm.setTargets("") }, modifier = Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(stringResource(R.string.cf_count), form.cfCount, { v -> vm.updateForm { it.copy(cfCount = v.filter(Char::isDigit).take(5)) } },
                        Modifier.width(110.dp), number = true)
                    Spacer(Modifier.width(6.dp))
                    ScanButton(onClick = { vm.fillRandomCloudflare() }) { Text(stringResource(R.string.cf_random)) }
                    Spacer(Modifier.width(4.dp))
                    ScanButton(onClick = { vm.fillRandomCloudflare(v6 = true) }) { Text("IPv6") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(stringResource(R.string.domain), form.domain, { v -> vm.updateForm { it.copy(domain = v) } }, Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    ScanButton(onClick = { vm.resolveAndAdd() }) { Text(stringResource(R.string.resolve_add)) }
                }
                GroupFillRow("ip") { vm.setTargets(it.joinToString("\n")) }
                SaveAsGroupRow("ip") { form.targets.lines().map { it.trim() }.filter { it.isNotEmpty() } }
            }
        }

        item(key = "methods") {
            GlassCard(Modifier.entrance(2)) {
                SectionLabel(stringResource(R.string.methods))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Method.entries.forEach { m ->
                        FilterChip(m.id in form.methods, {
                            vm.updateForm { f -> f.copy(methods = if (m.id in f.methods) f.methods - m.id else f.methods + m.id) }
                        }, label = { Text(m.label) })
                    }
                }
                Row {
                    Field(stringResource(R.string.ports), form.ports, { v -> vm.updateForm { it.copy(ports = v) } }, Modifier.weight(1f),
                        error = if (form.portError) invalidMsg else null, placeholder = "443, 2053, 8443")
                    Spacer(Modifier.width(8.dp))
                    Field(stringResource(R.string.http_path), form.path, { v -> vm.updateForm { it.copy(path = v) } }, Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(form.ports == "443", { vm.updateForm { it.copy(ports = "443") } }, label = { Text("443") })
                    FilterChip(false, { vm.updateForm { it.copy(ports = "443, 2053, 2083, 2087, 2096, 8443") } }, label = { Text(stringResource(R.string.port_cf_https)) })
                    FilterChip(false, { vm.updateForm { it.copy(ports = "80, 8080, 8880, 2052, 2082, 2086, 2095") } }, label = { Text(stringResource(R.string.port_cf_http)) })
                }
                Field(stringResource(R.string.hostname_sni), form.hostname, { v -> vm.updateForm { it.copy(hostname = v) } }, Modifier.fillMaxWidth(),
                    error = if (form.hostError) invalidMsg else null)
                SectionLabel(stringResource(R.string.preset))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanConfig.PRESETS.forEach { p -> FilterChip(form.preset == p, { vm.applyPreset(p) }, label = { Text(presetLabel(p)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.workers), form.workers, { v -> vm.updateForm { it.copy(workers = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.workersError) "1-256" else null)
                    Field(stringResource(R.string.timeout_s), form.timeoutS, { v -> vm.updateForm { it.copy(timeoutS = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.timeoutError) "0.3-30" else null)
                    Field(stringResource(R.string.attempts), form.attempts, { v -> vm.updateForm { it.copy(attempts = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.attemptsError) "1-20" else null)
                }
                IconTextButton(stringResource(R.string.advanced), if (showAdvanced) AppIconKind.ChevronUp else AppIconKind.ChevronDown, { showAdvanced = !showAdvanced })
                androidx.compose.animation.AnimatedVisibility(showAdvanced,
                    enter = androidx.compose.animation.expandVertically(
                        androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.85f)) +
                        androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically(
                        androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(130))) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow(stringResource(R.string.adaptive_timeout), stringResource(R.string.adaptive_timeout_desc), form.adaptive) { v -> vm.updateForm { it.copy(adaptive = v) } }
                        SwitchRow(stringResource(R.string.fail_fast), stringResource(R.string.fail_fast_desc), form.failFast) { v -> vm.updateForm { it.copy(failFast = v) } }
                        SwitchRow(stringResource(R.string.sample_large), stringResource(R.string.sample_large_desc), form.sample) { v -> vm.updateForm { it.copy(sample = v) } }
                        Field(stringResource(R.string.max_hosts), form.maxHosts, { v -> vm.updateForm { it.copy(maxHosts = v.filter(Char::isDigit).take(6)) } },
                            Modifier.fillMaxWidth(), number = true, error = if (form.maxHostsError) "1-262144" else null)
                        Field(stringResource(R.string.delay_ms), form.delayMs, { v -> vm.updateForm { it.copy(delayMs = v.filter(Char::isDigit).take(5)) } },
                            Modifier.fillMaxWidth(), number = true, error = if (form.delayError) "0-5000" else null)
                        Text(stringResource(R.string.explanation_https), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.explanation_accuracy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val startBtnInteraction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    PrimaryButton(onClick = { vm.startScan() }, enabled = !running && form.valid && form.targets.isNotBlank(),
                        interactionSource = startBtnInteraction,
                        modifier = Modifier.weight(1f).pressScale(startBtnInteraction)) {
                        AppIcon(AppIconKind.Play, MaterialTheme.colorScheme.onPrimary, size = 16.dp)
                        Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.start_scan))
                    }
                    if (running) {
                        Spacer(Modifier.width(8.dp))
                        ScanButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }

        if (running) item(key = "progress") {
            ProgressCard(progress.phase.ifEmpty { stringResource(R.string.scanning) }, progress.done, progress.total, progress.startedAt) { vm.cancel() }
        }

        if (notes.isNotBlank()) item(key = "notes") {
            GlassCard { LtrText(notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }

        if (results.isNotEmpty()) {
            item(key = "stats") {
                val working = results.count { it.working }
                val best = results.filter { it.working }.mapNotNull { it.primaryMs() }.minOrNull()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedStatBox(stringResource(R.string.stat_tested), results.size, Modifier.weight(1f))
                    AnimatedStatBox(stringResource(R.string.stat_working), working, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    StatBox(stringResource(R.string.stat_best), best?.let { "${Fmt.ms(it)} ms" } ?: "-", Modifier.weight(1f))
                }
            }
            item(key = "actions") {
                GlassCard {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconTextButton(stringResource(R.string.deep_verify), AppIconKind.Check, { vm.deepVerify() }, enabled = !running)
                        IconTextButton(stringResource(R.string.rescan_failed), AppIconKind.Refresh, { vm.rescanFailed() }, enabled = !running)
                        IconTextButton(stringResource(R.string.speed_test), AppIconKind.Speed, { vm.speedTestTop() }, enabled = !running)
                        IconTextButton(stringResource(R.string.clear), AppIconKind.Trash, { vm.clearResults() }, enabled = !running)
                    }
                    Field(stringResource(R.string.search_results), filter.query, { q -> vm.setFilter { it.copy(query = q) } }, Modifier.fillMaxWidth(),
                        placeholder = "104.16 · DE · AS13335")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DropDown(stringResource(R.string.sort_by), SortKey.entries.map { it.name }, filter.sort.name,
                            { k -> vm.setFilter { it.copy(sort = SortKey.valueOf(k)) } }, display = { sortLabel(SortKey.valueOf(it)) })
                        FilterChip(filter.ascending, { vm.setFilter { it.copy(ascending = !it.ascending) } }, label = { Text(stringResource(R.string.reverse)) })
                        FilterChip(filter.workingOnly, { vm.setFilter { it.copy(workingOnly = !it.workingOnly) } }, label = { Text(stringResource(R.string.working_only)) })
                        listOf(150, 300, 600).forEach { ms ->
                            FilterChip(filter.maxLatencyMs == ms, { vm.setFilter { it.copy(maxLatencyMs = if (it.maxLatencyMs == ms) null else ms) } },
                                label = { Text("≤ $ms ms") })
                        }
                    }
                    ExportBar("scanon-ips", { vm.ipList(it) }, { f, w -> vm.export(f, w) })
                    Text(stringResource(R.string.tap_to_copy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (visible.isEmpty()) item(key = "nomatch") { EmptyState(AppIconKind.Search, stringResource(R.string.no_matches)) }
            items(visible, key = { it.key }) { r ->
                IpReportCard(r, form.hostname, Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.spring(
                        stiffness = 300f, dampingRatio = 0.8f),
                    placementSpec = androidx.compose.animation.core.spring(
                        stiffness = 300f, dampingRatio = 0.8f)))
            }
        } else if (!running) {
            item(key = "empty") { EmptyState(AppIconKind.Globe, stringResource(R.string.ip_empty)) }
        }
    }
}

@Composable
fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}
