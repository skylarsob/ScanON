@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.skyler.scanon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.skyler.scanon.engine.DnsEngine
import com.skyler.scanon.engine.DnsReport
import com.skyler.scanon.engine.Fmt
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
import com.skyler.scanon.ui.components.GeoLine
import com.skyler.scanon.ui.components.GlassCard
import com.skyler.scanon.ui.components.LocalSnack
import com.skyler.scanon.ui.components.LtrText
import com.skyler.scanon.ui.components.ProgressCard
import com.skyler.scanon.ui.components.SectionLabel
import com.skyler.scanon.ui.components.StatBox
import com.skyler.scanon.ui.components.copyToClipboard
import com.skyler.scanon.ui.components.shareText
import com.skyler.scanon.ui.components.flashOnAppear
import com.skyler.scanon.ui.components.latencyColor
import com.skyler.scanon.ui.components.pressScale
import com.skyler.scanon.ui.components.rememberHaptics
import com.skyler.scanon.ui.theme.Bad
import com.skyler.scanon.ui.theme.Good
import com.skyler.scanon.ui.theme.Warn
import com.skyler.scanon.ui.vm.DnsScanViewModel
import com.skyler.scanon.ui.vm.DomainScanViewModel
import com.skyler.scanon.ui.vm.ResolverRow

@Composable
fun DnsScanScreen(vm: DnsScanViewModel = viewModel()) {
    val snack = LocalSnack.current
    val haptics = rememberHaptics()
    val form by vm.form.collectAsStateWithLifecycle()
    val domainWarn by vm.domainWarn.collectAsStateWithLifecycle()
    val iranTotal by vm.iranTotal.collectAsStateWithLifecycle()
    val iranRangeTotal by vm.iranRangeTotal.collectAsStateWithLifecycle()
    var iranCustom by rememberSaveable { mutableStateOf("") }
    var iranRangeCustom by rememberSaveable { mutableStateOf("") }
    val results by vm.results.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var wasRunning by rememberSaveable { mutableStateOf(running) }
    LaunchedEffect(vm) { vm.events.collect { snack(it) } }
    LaunchedEffect(running) { if (wasRunning && !running) haptics.success(); wasRunning = running }
    val invalid = stringResource(R.string.err_invalid_value)
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.loadFile(it) } }
    val dnsContext = LocalContext.current
    val copiedMsg = stringResource(R.string.copied)

    if (domainWarn) {
        AlertDialog(
            onDismissRequest = { vm.dismissDomainWarn() },
            title = { Text(stringResource(R.string.warn_domain_title)) },
            text = { Text(stringResource(R.string.warn_domain_text)) },
            confirmButton = { PrimaryButton(onClick = { vm.confirmDomainWarn() }) { Text(stringResource(R.string.warn_scan_anyway)) } },
            dismissButton = { TextButton(onClick = { vm.dismissDomainWarn() }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "iran") {
            GlassCard(Modifier.entrance(0)) {
                SectionLabel(stringResource(R.string.iran_title))
                Text(stringResource(R.string.iran_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (iranTotal > 0) LtrText(stringResource(R.string.iran_count, iranTotal), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100, 250, 500, 1000).forEach { n ->
                        ScanButton(onClick = { vm.loadIranSample(n) }, enabled = !running) { Text("$n") }
                    }
                    ScanButton(onClick = { vm.loadIranSample(Int.MAX_VALUE) }, enabled = !running) { Text(stringResource(R.string.iran_add_all)) }
                }
                // Custom amount (3.0): any sample size, not just the chips.
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.iran_custom), iranCustom, { v -> iranCustom = v.filter(Char::isDigit).take(5) },
                        Modifier.weight(1f), number = true)
                    ScanButton(
                        onClick = { iranCustom.toIntOrNull()?.takeIf { it > 0 }?.let { vm.loadIranSample(it) } },
                        enabled = !running && (iranCustom.toIntOrNull() ?: 0) > 0,
                    ) { Text(stringResource(R.string.iran_load)) }
                }
                // Ranges of those resolvers (3.0): fills the custom-range field
                // further down; the scan expands each /24 and tries every address.
                SectionLabel(stringResource(R.string.iran_ranges_title))
                Text(stringResource(R.string.iran_ranges_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (iranRangeTotal > 0) LtrText(stringResource(R.string.iran_ranges_count, iranRangeTotal), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(25, 50, 100).forEach { n ->
                        ScanButton(onClick = { vm.loadIranRanges(n) }, enabled = !running) { Text("$n") }
                    }
                    ScanButton(onClick = { vm.loadIranRanges(Int.MAX_VALUE) }, enabled = !running) { Text(stringResource(R.string.iran_add_all)) }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.iran_custom), iranRangeCustom, { v -> iranRangeCustom = v.filter(Char::isDigit).take(5) },
                        Modifier.weight(1f), number = true)
                    ScanButton(
                        onClick = { iranRangeCustom.toIntOrNull()?.takeIf { it > 0 }?.let { vm.loadIranRanges(it) } },
                        enabled = !running && (iranRangeCustom.toIntOrNull() ?: 0) > 0,
                    ) { Text(stringResource(R.string.iran_load)) }
                }
            }
        }
        item(key = "tunnel") {
            GlassCard(Modifier.entrance(1)) {
                SectionLabel(stringResource(R.string.tunnel_title))
                Text(stringResource(R.string.tunnel_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(form.tunnel.isEmpty(), { vm.clearTunnel() }, label = { Text(stringResource(R.string.tunnel_off)) })
                    com.skyler.scanon.engine.TunnelModes.ALL.forEach { p ->
                        FilterChip(form.tunnel == p.id, { vm.applyTunnelMode(p.id) }, label = { Text(p.name) })
                    }
                }
                val ranked = vm.tunnelRanked()
                if (form.tunnel.isNotEmpty() && ranked.isNotEmpty()) {
                    val best = ranked.first()
                    SectionLabel(stringResource(R.string.tunnel_best))
                    LtrText("${best.server} · ${Fmt.ms(best.medianMs)} ms · ok ${best.successes}/${best.queries}" +
                        (if (best.e2e.isNotEmpty()) " · E2E ${best.e2e}" else ""),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScanButton(onClick = { copyToClipboard(dnsContext, best.server); snack(copiedMsg) }) { Text(stringResource(R.string.tunnel_copy_best)) }
                        ScanButton(onClick = { shareText(dnsContext, vm.tunnelReportText(), com.skyler.scanon.engine.ExportFormat.TXT, "scanon-tunnel") }) { Text(stringResource(R.string.tunnel_share)) }
                    }
                }
            }
        }
        item(key = "form") {
            GlassCard(Modifier.entrance(2)) {
                SectionLabel(stringResource(R.string.dns_servers_title))
                Field(stringResource(R.string.servers_hint), form.servers, { v -> vm.update { it.copy(servers = v) } }, Modifier.fillMaxWidth(),
                    singleLine = false, minLines = 3, maxLines = 8,
                    error = form.invalidServers.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.err_bad_servers, it.take(3).joinToString()) })
                LtrText(stringResource(R.string.dns_count, form.serverList.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionLabel(stringResource(R.string.resolver_list))
                // One aligned row of equal-width buttons (2.9.1).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanButton(onClick = { vm.fillKnown() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dns_presets), maxLines = 1) }
                    ScanButton(onClick = { vm.fillKnown(includeV6 = true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dns_presets_v6), maxLines = 1) }
                    ScanButton(onClick = { importPicker.launch("text/*") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.import_list), maxLines = 1) }
                }
                Field(stringResource(R.string.custom_range), form.ranges, { v -> vm.update { it.copy(ranges = v) } }, Modifier.fillMaxWidth(),
                    singleLine = false, minLines = 1, maxLines = 4, placeholder = "185.51.200.0/24")
                Text(stringResource(R.string.custom_range_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GroupFillRow("dns") { items -> vm.update { it.copy(servers = items.joinToString("\n")) } }
                SaveAsGroupRow("dns") { form.serverList }
                Field(stringResource(R.string.test_domain), form.domain, { v -> vm.update { it.copy(domain = v) } }, Modifier.fillMaxWidth(),
                    error = if (form.domainError) invalid else null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DropDown(stringResource(R.string.record_type), listOf("Auto") + DnsEngine.QTYPES.keys, form.qtype, { q -> vm.update { it.copy(qtype = q) } })
                    DropDown(stringResource(R.string.protocol), listOf("Auto", "udp", "tcp", "both", "dot", "doh"), form.protocol,
                        { p -> vm.update { it.copy(protocol = p) } }, display = { if (it == "Auto") it else if (it == "both") "UDP+TCP" else it.uppercase() })
                }
                SectionLabel(stringResource(R.string.speed_presets))
                val activePreset = when {
                    form.attempts == "3" && form.timeoutS == "2" && form.workers == "32" -> "fast"
                    form.attempts == "7" && form.timeoutS == "5" && form.workers == "16" -> "thorough"
                    form.attempts == "5" && form.timeoutS == "3" && form.workers == "24" -> "balanced"
                    else -> ""
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(activePreset == "fast", { vm.applyDnsPreset("fast") }, label = { Text(stringResource(R.string.dns_preset_fast)) })
                    FilterChip(activePreset == "balanced", { vm.applyDnsPreset("balanced") }, label = { Text(stringResource(R.string.preset_balanced)) })
                    FilterChip(activePreset == "thorough", { vm.applyDnsPreset("thorough") }, label = { Text(stringResource(R.string.dns_preset_thorough)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.attempts), form.attempts, { v -> vm.update { it.copy(attempts = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.attemptsError) "1-20" else null)
                    Field(stringResource(R.string.timeout_s), form.timeoutS, { v -> vm.update { it.copy(timeoutS = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.timeoutError) "0.3-30" else null)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.workers), form.workers, { v -> vm.update { it.copy(workers = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.workersError) "1-128" else null)
                    Field(stringResource(R.string.dns_port), form.port, { v -> vm.update { it.copy(port = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.portError) "1-65535" else null)
                }
                SwitchRow(stringResource(R.string.shuffle), stringResource(R.string.shuffle_desc), form.shuffle) { v -> vm.update { it.copy(shuffle = v) } }
                SwitchRow(stringResource(R.string.nearby), stringResource(R.string.nearby_desc), form.nearby) { v -> vm.update { it.copy(nearby = v) } }
                SwitchRow(stringResource(R.string.e2e), stringResource(R.string.e2e_desc), form.e2e) { v -> vm.update { it.copy(e2e = v) } }
                if (form.e2e) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.e2e_timeout), form.e2eTimeoutMs, { v -> vm.update { it.copy(e2eTimeoutMs = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.e2eTimeoutError) "500-30000" else null)
                    Field(stringResource(R.string.e2e_concurrency), form.e2eConcurrency, { v -> vm.update { it.copy(e2eConcurrency = v) } }, Modifier.weight(1f),
                        number = true, error = if (form.e2eConcError) "1-10" else null)
                }
                if (form.protocol.equals("doh", true) || form.protocol == "Auto")
                    Field(stringResource(R.string.doh_hostname), form.dohHost, { v -> vm.update { it.copy(dohHost = v) } }, Modifier.fillMaxWidth())
                Text(stringResource(R.string.explanation_dns), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    val dnsBtnInteraction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    PrimaryButton(onClick = { vm.start() }, enabled = !running && form.valid,
                        interactionSource = dnsBtnInteraction,
                        modifier = Modifier.weight(1f).pressScale(dnsBtnInteraction)) { Text(stringResource(R.string.start_scan)) }
                    if (running) { Spacer(Modifier.width(8.dp)); ScanButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.cancel)) } }
                }
            }
        }
        if (running) item(key = "progress") { ProgressCard(stringResource(R.string.scanning), progress.done, progress.total, progress.startedAt) { vm.cancel() } }
        if (results.isNotEmpty()) {
            item(key = "stats") {
                val ok = results.filter { it.working }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedStatBox(stringResource(R.string.stat_tested), results.size, Modifier.weight(1f))
                    AnimatedStatBox(stringResource(R.string.stat_working), ok.size, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    StatBox(stringResource(R.string.stat_best), ok.mapNotNull { it.medianMs }.minOrNull()?.let { "${Fmt.ms(it)} ms" } ?: "-", Modifier.weight(1f))
                }
            }
            item(key = "export") {
                GlassCard {
                    ExportBar("scanon-dns", { vm.serverList(it) }, { f, w -> vm.export(f, w) })
                    Text(stringResource(R.string.tap_to_copy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(results, key = { it.server + it.protocol + it.qtype }) { r ->
                DnsReportCard(r, Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f),
                    placementSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f)),
                    tunnelMode = form.tunnel.isNotEmpty())
            }
        } else if (!running) item(key = "empty") { EmptyState(AppIconKind.Dns, stringResource(R.string.dns_empty)) }
    }
}

@Composable
fun DnsReportCard(r: DnsReport, modifier: Modifier = Modifier, tunnelMode: Boolean = false) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val copied = stringResource(R.string.copied)
    val cardInteraction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    GlassCard(modifier
        .flashOnAppear()
        .pressScale(cardInteraction)
        .clickable(cardInteraction, androidx.compose.foundation.LocalIndication.current) {
            copyToClipboard(context, r.server); snack(copied) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LtrText(r.server, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                GeoLine(r.server)
            }
            Column(horizontalAlignment = Alignment.End) {
                LtrText("${Fmt.ms(r.medianMs)} ms", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = latencyColor(r.medianMs))
                LtrText("${r.protocol.uppercase()} · ${r.qtype}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LtrText(
            "ok ${r.successes}/${r.queries} · loss ${Fmt.pct(r.lossPct)} · min ${Fmt.ms(r.minMs)} · avg ${Fmt.ms(r.avgMs)} · max ${Fmt.ms(r.maxMs)} · jitter ${Fmt.ms(r.jitterMs)}" +
                (if (r.rcodeName.isNotEmpty()) " · ${r.rcodeName}" else "") + (if (r.dnssecAd) " · AD" else ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tunnelMode && r.working) LtrText(stringResource(R.string.tunnel_score, com.skyler.scanon.engine.tunnelScore(r)),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (r.proto.isNotEmpty())
            LtrText(r.proto, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.qtype == "Tunnel" && r.lastDetail.isNotEmpty())
            LtrText(r.lastDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.e2e.isNotEmpty()) LtrText("E2E ${r.e2e}", style = MaterialTheme.typography.bodySmall,
            color = if (r.e2e.startsWith("pass")) Good else Bad)
        r.answers.take(6).forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { copyToClipboard(context, a.data); snack(copied) }) {
                RecordTypePill(a.type)
                Spacer(Modifier.width(8.dp))
                LtrText(a.data, style = MaterialTheme.typography.bodySmall.merge(com.skyler.scanon.ui.components.MonoLtr), modifier = Modifier.weight(1f))
                LtrText("ttl ${a.ttl}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (r.poisoned) Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIconKind.Warn, Bad, size = 16.dp); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.poisoned_warn), color = Bad, style = MaterialTheme.typography.bodySmall)
        }
        if (r.hijack) Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIconKind.Warn, Bad, size = 16.dp); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.hijack_warn), color = Bad, style = MaterialTheme.typography.bodySmall)
        }
        if (r.failures.isNotEmpty()) LtrText("failures: ${r.failures.entries.joinToString { "${it.key}×${it.value}" }}" +
            (if (r.lastDetail.isNotEmpty()) " · ${r.lastDetail}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun DomainScanScreen(vm: DomainScanViewModel = viewModel()) {
    val domain by vm.domain.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val ips by vm.ips.collectAsStateWithLifecycle()
    val modal by vm.modal.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val skipped by vm.skipped.collectAsStateWithLifecycle()
    var v6 by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "form") {
            GlassCard(Modifier.entrance(0)) {
                Text(stringResource(R.string.domain_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Field(stringResource(R.string.domain), domain, { vm.domain.value = it }, Modifier.fillMaxWidth(), placeholder = "example.com")
                FilterChip(v6, { v6 = !v6 }, label = { Text(stringResource(R.string.include_ipv6)) })
                Row {
                    val domainBtnInteraction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    PrimaryButton(onClick = { vm.run(v6) }, enabled = !running && domain.isNotBlank(),
                        interactionSource = domainBtnInteraction,
                        modifier = Modifier.weight(1f).pressScale(domainBtnInteraction)) { Text(stringResource(R.string.domain_run)) }
                    if (running) { Spacer(Modifier.width(8.dp)); ScanButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.cancel)) } }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (running) item(key = "progress") { ProgressCard(phase.ifEmpty { stringResource(R.string.scanning) }, progress.done, progress.total, progress.startedAt) { vm.cancel() } }
        if (rows.isNotEmpty()) {
            item(key = "rhead") { SectionLabel(stringResource(R.string.resolver_answers)) }
            items(rows, key = { "r" + it.server }) { row ->
                ResolverRowCard(row, modal, Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f),
                    placementSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f)))
            }
        }
        if (skipped.isNotEmpty()) item(key = "skipped") {
            GlassCard { LtrText(stringResource(R.string.domain_skipped_bogon, skipped.joinToString()), style = MaterialTheme.typography.bodySmall, color = Bad) }
        }
        if (ips.isNotEmpty()) {
            item(key = "ihead") {
                GlassCard {
                    SectionLabel(stringResource(R.string.domain_ips_title))
                    ExportBar("scanon-domain", { w -> ips.filter { !w || it.working }.joinToString("\n") { it.ip } }, { f, w -> vm.export(f, w) })
                }
            }
            items(ips, key = { "i" + it.key }) { r ->
                IpReportCard(r, domain, Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f),
                    placementSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f)))
            }
        }
        if (!running && rows.isEmpty() && error.isEmpty()) item(key = "empty") { EmptyState(AppIconKind.Target, stringResource(R.string.domain_empty)) }
    }
}

@Composable
fun ResolverRowCard(row: ResolverRow, modal: Set<String>, modifier: Modifier = Modifier) {
    val divergent = row.ok && !row.poisoned && row.aAnswers.isNotEmpty() && modal.isNotEmpty() && row.aAnswers.toSet() != modal
    GlassCard(modifier.flashOnAppear()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                row.poisoned -> AppIcon(AppIconKind.Warn, Bad, size = 18.dp)
                divergent -> AppIcon(AppIconKind.Warn, Warn, size = 18.dp)
                row.ok -> AppIcon(AppIconKind.Check, Good, size = 18.dp)
                else -> AppIcon(AppIconKind.Close, Bad, size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            LtrText(row.server + if (row.label.isNotEmpty()) " (${row.label})" else "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            LtrText("${Fmt.ms(row.avgMs)} ms", color = latencyColor(row.avgMs))
        }
        if (row.poisoned) Text(stringResource(R.string.poisoned_warn), color = Bad, style = MaterialTheme.typography.bodySmall)
        else if (divergent) Text(stringResource(R.string.divergent_warn), color = Warn, style = MaterialTheme.typography.bodySmall)
        if (row.protocol == "doh") Text(stringResource(R.string.answered_doh), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (row.aAnswers.isEmpty() && row.aaaaAnswers.isEmpty()) Text(stringResource(R.string.no_address_answers), style = MaterialTheme.typography.bodySmall)
        if (row.aAnswers.isNotEmpty()) Row(verticalAlignment = Alignment.Top) {
            RecordTypePill("A"); Spacer(Modifier.width(8.dp))
            LtrText(row.aAnswers.joinToString(", "), style = MaterialTheme.typography.bodySmall.merge(com.skyler.scanon.ui.components.MonoLtr))
        }
        if (row.aaaaAnswers.isNotEmpty()) Row(verticalAlignment = Alignment.Top) {
            RecordTypePill("AAAA"); Spacer(Modifier.width(8.dp))
            LtrText(row.aaaaAnswers.joinToString(", "), style = MaterialTheme.typography.bodySmall.merge(com.skyler.scanon.ui.components.MonoLtr))
        }
    }
}

/** Colour-coded DNS record type pill (2.5): A cyan/teal family, AAAA indigo, CNAME violet, MX amber, TXT green, NS pink. */
@Composable
fun RecordTypePill(type: String) {
    val color = when (type.uppercase()) {
        "A" -> MaterialTheme.colorScheme.primary
        "AAAA" -> MaterialTheme.colorScheme.secondary
        "CNAME" -> com.skyler.scanon.ui.theme.Violet
        "MX" -> Color(0xFFF59E0B)
        "TXT" -> Color(0xFF4ADE80)
        "NS" -> Color(0xFFF471B5)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        type.uppercase(),
        style = MaterialTheme.typography.labelSmall.merge(com.skyler.scanon.ui.components.MonoLtr),
        fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
