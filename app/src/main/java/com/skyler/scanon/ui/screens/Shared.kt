@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.skyler.scanon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyler.scanon.R
import com.skyler.scanon.engine.Fmt
import com.skyler.scanon.engine.TargetReport
import com.skyler.scanon.ui.components.ScanButton
import com.skyler.scanon.ui.components.AppIcon
import com.skyler.scanon.ui.components.AppIconKind
import com.skyler.scanon.ui.components.Field
import com.skyler.scanon.ui.components.GeoLine
import com.skyler.scanon.ui.components.GlassCard
import com.skyler.scanon.ui.components.LocalSnack
import com.skyler.scanon.ui.components.LtrText
import com.skyler.scanon.ui.components.copyToClipboard
import com.skyler.scanon.ui.components.flashOnAppear
import com.skyler.scanon.ui.components.pressScale
import com.skyler.scanon.ui.components.latencyColor
import com.skyler.scanon.ui.components.rememberHaptics
import com.skyler.scanon.ui.components.reliabilityColor
import com.skyler.scanon.ui.vm.GroupsViewModel
import com.skyler.scanon.ui.vm.VaultViewModel

/** Localised reliability word (the raw value is still used in exports). */
@Composable
fun reliabilityLabel(r: String): String = when (r) {
    "success" -> stringResource(R.string.rel_success)
    "partial" -> stringResource(R.string.rel_partial)
    "unstable" -> stringResource(R.string.rel_unstable)
    "failed" -> stringResource(R.string.rel_failed)
    "inconclusive" -> stringResource(R.string.rel_inconclusive)
    else -> stringResource(R.string.rel_untested)
}

/** One IP result: tap copies, star saves to the Vault, chevron expands the full measurement breakdown. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun IpReportCard(r: TargetReport, sniHostname: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val haptics = rememberHaptics()
    val vault: VaultViewModel = viewModel()
    val copied = stringResource(R.string.copied)
    var expanded by rememberSaveable(r.key) { mutableStateOf(false) }
    var showInspector by remember { mutableStateOf(false) }
    val cardInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    GlassCard(modifier
        .flashOnAppear()
        .pressScale(cardInteraction)
        .clickable(cardInteraction, androidx.compose.foundation.LocalIndication.current) {
            copyToClipboard(context, r.ip); haptics.tick(); snack(copied) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.skyler.scanon.ui.components.StatusDot(reliabilityColor(r.reliability))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                LtrText(r.endpoint(), style = MaterialTheme.typography.titleMedium.merge(com.skyler.scanon.ui.components.MonoLtr), fontWeight = FontWeight.Bold)
                GeoLine(r.ip)
            }
            Column(horizontalAlignment = Alignment.End) {
                LtrText("${Fmt.ms(r.primaryMs())} ms", style = MaterialTheme.typography.titleMedium.merge(com.skyler.scanon.ui.components.MonoLtr),
                    fontWeight = FontWeight.Bold, color = latencyColor(r.primaryMs()))
                Text("${r.method.uppercase()} · ${reliabilityLabel(r.reliability)}", style = MaterialTheme.typography.labelSmall,
                    color = reliabilityColor(r.reliability), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(AppIconKind.Star, MaterialTheme.colorScheme.primary,
                    Modifier.clickable { vault.add(r.ip, "", sniHostname.ifEmpty { "cloudflare.com" }, r.port); haptics.success() })
                Spacer(Modifier.width(4.dp))
                Row {
                    AppIcon(AppIconKind.Info, MaterialTheme.colorScheme.onSurfaceVariant,
                        Modifier.clickable { showInspector = true })
                    Spacer(Modifier.width(4.dp))
                    AppIcon(if (expanded) AppIconKind.ChevronUp else AppIconKind.ChevronDown, MaterialTheme.colorScheme.onSurfaceVariant,
                        Modifier.clickable { expanded = !expanded })
                }
            }
        }
        LtrText(
            "ok ${r.successes}/${r.tests} · loss ${Fmt.pct(r.loss)} · jitter ${Fmt.ms(r.jitterMs)} ms · score ${Fmt.ms(r.score())}" +
                (r.downloadMbps?.let { " · ${Fmt.num(it)} Mbps" } ?: ""),
            style = MaterialTheme.typography.bodySmall.merge(com.skyler.scanon.ui.components.MonoLtr), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (r.totalSamples.size >= 2) {
            com.skyler.scanon.ui.components.Sparkline(
                r.totalSamples, latencyColor(r.primaryMs()),
                Modifier.fillMaxWidth().height(24.dp))
        }
        androidx.compose.animation.AnimatedVisibility(expanded,
            enter = androidx.compose.animation.expandVertically(
                androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.85f)) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(
                androidx.compose.animation.core.tween(160)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val lines = buildList {
                    add("TCP median ${Fmt.ms(r.connectMedian)} ms (min ${Fmt.ms(r.connectMin)} / max ${Fmt.ms(r.connectMax)})")
                    if (r.tlsAvg != null || r.ttfbAvg != null) add("TLS ${Fmt.ms(r.tlsAvg)} ms · TTFB ${Fmt.ms(r.ttfbAvg)} ms · HTTP ${r.statusCode ?: "-"}")
                    if (r.tlsVersion != null) add("${r.tlsVersion} · ${r.cipher ?: "?"}" + (r.certDaysLeft?.let { " · cert $it d left" } ?: ""))
                    if (r.totalSamples.isNotEmpty()) add("total runs (ms): ${r.totalSamples.joinToString(" ") { Fmt.ms(it) }} · p90 ${Fmt.ms(r.totalP90)}")
                    if (r.failures.isNotEmpty()) add("failures: ${r.failures.entries.joinToString { "${it.key}×${it.value}" }}")
                    if (r.lastDetail.isNotEmpty()) add(r.lastDetail)
                }
                lines.forEach { LtrText(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (showInspector) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showInspector = false }, sheetState = sheetState) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.skyler.scanon.ui.components.StatusDot(reliabilityColor(r.reliability))
                    Spacer(Modifier.width(8.dp))
                    LtrText(r.endpoint(), style = MaterialTheme.typography.titleLarge.merge(com.skyler.scanon.ui.components.MonoLtr),
                        fontWeight = FontWeight.Bold)
                }
                GeoLine(r.ip)
                if (r.totalSamples.size >= 2) {
                    com.skyler.scanon.ui.components.Sparkline(
                        r.totalSamples, latencyColor(r.primaryMs()),
                        Modifier.fillMaxWidth().height(56.dp))
                }
                val rows = buildList {
                    add(stringResource(R.string.details) + " · " + r.method.uppercase() to "${Fmt.ms(r.primaryMs())} ms · ${reliabilityLabel(r.reliability)}")
                    add("ok / loss / jitter" to "${r.successes}/${r.tests} · ${Fmt.pct(r.loss)} · ${Fmt.ms(r.jitterMs)} ms")
                    add("score / p90" to "${Fmt.ms(r.score())} · ${Fmt.ms(r.totalP90)} ms")
                    add("TCP median (min-max)" to "${Fmt.ms(r.connectMedian)} ms (${Fmt.ms(r.connectMin)}-${Fmt.ms(r.connectMax)})")
                    if (r.tlsAvg != null || r.ttfbAvg != null)
                        add("TLS / TTFB / HTTP" to "${Fmt.ms(r.tlsAvg)} ms · ${Fmt.ms(r.ttfbAvg)} ms · ${r.statusCode ?: "-"}")
                    if (r.tlsVersion != null)
                        add("TLS" to "${r.tlsVersion} · ${r.cipher ?: "?"}" + (r.certDaysLeft?.let { " · cert $it d" } ?: ""))
                    r.downloadMbps?.let { add("download" to "${Fmt.num(it)} Mbps") }
                    if (r.totalSamples.isNotEmpty())
                        add("runs (ms)" to r.totalSamples.joinToString(" ") { Fmt.ms(it) })
                    if (r.failures.isNotEmpty())
                        add("failures" to r.failures.entries.joinToString { "${it.key}×${it.value}" })
                    if (r.lastDetail.isNotEmpty()) add("detail" to r.lastDetail)
                }
                rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        LtrText(value, style = MaterialTheme.typography.bodyMedium.merge(com.skyler.scanon.ui.components.MonoLtr),
                            fontWeight = FontWeight.Bold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanButton(onClick = {
                        copyToClipboard(context, r.ip); snack(copied)
                    }) { Text(stringResource(R.string.copy_ips)) }
                    ScanButton(onClick = {
                        copyToClipboard(context, r.endpoint()); snack(copied)
                    }) { Text(stringResource(R.string.copy_report)) }
                    ScanButton(onClick = {
                        vault.add(r.ip, "", sniHostname.ifEmpty { "cloudflare.com" }, r.port); haptics.success()
                    }) { Text(stringResource(R.string.save_vault)) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Chips that fill a scanner from a saved group of the given kind ("ip" or "dns"). */
@Composable
fun GroupFillRow(kind: String, onFill: (List<String>) -> Unit) {
    val vm: GroupsViewModel = viewModel()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val mine = groups.filter { it.kind == kind }
    if (mine.isEmpty()) return
    Column {
        Text(stringResource(R.string.groups_fill), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            mine.forEach { g -> AssistChip(onClick = { onFill(g.items) }, label = { Text("${g.name} (${g.items.size})") }) }
        }
    }
}

/** "Save current list as a group" inline form. */
@Composable
fun SaveAsGroupRow(kind: String, currentItems: () -> List<String>) {
    val vm: GroupsViewModel = viewModel()
    val snack = LocalSnack.current
    val savedMsg = stringResource(R.string.groups_saved)
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.groups_save_as)) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Field(stringResource(R.string.groups_name), name, { name = it }, Modifier.weight(1f), ltr = false)
        TextButton(onClick = {
            val items = currentItems()
            if (name.isNotBlank() && items.isNotEmpty()) { vm.upsert(name, kind, items); snack(savedMsg); open = false; name = "" }
        }) { Text(stringResource(R.string.save_vault)) }
        TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
    }
}
