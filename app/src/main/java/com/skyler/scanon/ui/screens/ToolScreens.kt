@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.skyler.scanon.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyler.scanon.R
import com.skyler.scanon.data.GroupsStore
import com.skyler.scanon.data.VaultStore
import com.skyler.scanon.engine.ExportFormat
import com.skyler.scanon.engine.Fmt
import com.skyler.scanon.ui.components.PrimaryButton
import com.skyler.scanon.ui.components.ScanButton
import com.skyler.scanon.ui.components.AppIcon
import com.skyler.scanon.ui.components.AppIconKind
import com.skyler.scanon.ui.components.EmptyState
import com.skyler.scanon.ui.components.Field
import com.skyler.scanon.ui.components.entrance
import com.skyler.scanon.ui.components.GeoLine
import com.skyler.scanon.ui.components.GlassCard
import com.skyler.scanon.ui.components.IconTextButton
import com.skyler.scanon.ui.components.LocalSnack
import com.skyler.scanon.ui.components.LtrText
import com.skyler.scanon.ui.components.SectionLabel
import com.skyler.scanon.ui.components.SpinningRadar
import com.skyler.scanon.ui.components.copyToClipboard
import com.skyler.scanon.ui.components.latencyColor
import com.skyler.scanon.ui.components.rememberSaveFile
import com.skyler.scanon.ui.components.shareText
import com.skyler.scanon.ui.theme.Bad
import com.skyler.scanon.ui.theme.Good
import com.skyler.scanon.ui.theme.Neutral
import com.skyler.scanon.ui.vm.DiagViewModel
import com.skyler.scanon.ui.vm.GroupsViewModel
import com.skyler.scanon.ui.vm.HistoryViewModel
import com.skyler.scanon.ui.vm.VaultSort
import com.skyler.scanon.ui.vm.VaultViewModel

@Composable
fun DiagnosticsScreen(vm: DiagViewModel = viewModel()) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val snap by vm.snap.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val copied = stringResource(R.string.copied)
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "snap") {
            GlassCard(Modifier.entrance(0)) {
                SectionLabel(stringResource(R.string.snap_title))
                Text("${stringResource(R.string.snap_transport)}: ${snap.transport}" + if (snap.metered) " · ${stringResource(R.string.metered)}" else "")
                LtrText("${stringResource(R.string.local_ip)}: ${snap.privateIp}" + if (snap.privateIpv6 != "-") "  ·  ${snap.privateIpv6}" else "")
                LtrText("${stringResource(R.string.gateway)}: ${snap.gateway} · DNS: ${snap.dnsServers.joinToString().ifEmpty { "-" }}")
                Text("${stringResource(R.string.snap_vpn)}: ${if (snap.isVpn) yes else no} · ${stringResource(R.string.snap_validated)}: ${if (snap.validated) yes else no}")
                if (snap.downKbps > 0) LtrText("link estimate ↓ ${snap.downKbps / 1000} / ↑ ${snap.upKbps / 1000} Mbit/s", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.diag_refresh)) }
                    if (running) ScanButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.cancel)) }
                    else PrimaryButton(onClick = { vm.run() }) { Text(stringResource(R.string.diag_run)) }
                    if (checks.isNotEmpty() && !running) ScanButton(onClick = { copyToClipboard(context, vm.asText()); snack(copied) }) { Text(stringResource(R.string.copy_report)) }
                }
            }
        }
        items(vm.order, key = { it }) { id ->
            val ch = checks.firstOrNull { it.id == id }
            if (ch == null && !running) return@items
            GlassCard(Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f),
                    placementSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        ch == null -> SpinningRadar(MaterialTheme.colorScheme.primary)
                        ch.ok == true -> AppIcon(AppIconKind.Check, Good, size = 18.dp)
                        ch.ok == false -> AppIcon(AppIconKind.Close, Bad, size = 18.dp)
                        else -> AppIcon(AppIconKind.Info, Neutral, size = 18.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(ch?.name ?: stringResource(R.string.check_running), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    ch?.ms?.let { LtrText("${Fmt.ms(it)} ms", color = latencyColor(it)) }
                }
                ch?.let { LtrText(it.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Tiny trend line of the last retests (gaps = failed). */
@Composable
fun Sparkline(values: List<Double?>) {
    if (values.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    val ok = values.filterNotNull()
    if (ok.isEmpty()) return
    val max = ok.max().coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val step = size.width / (values.size - 1)
        var prev: Offset? = null
        values.forEachIndexed { i, v ->
            if (v == null) { prev = null; drawCircle(Bad, 3f, Offset(i * step, size.height - 2f)); return@forEachIndexed }
            val pt = Offset(i * step, size.height - (v / max * (size.height - 4)).toFloat() - 2f)
            prev?.let { drawLine(color, it, pt, 3f) }
            drawCircle(color, 3f, pt)
            prev = pt
        }
    }
}

@Composable
fun VaultScreen(vm: VaultViewModel = viewModel()) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val save = rememberSaveFile()
    val copied = stringResource(R.string.copied)
    val removedMsg = stringResource(R.string.removed_undo)
    var ip by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("cloudflare.com") }
    var port by rememberSaveable { mutableStateOf("443") }
    var editing by remember { mutableStateOf<VaultStore.Entry?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "head") {
            GlassCard(Modifier.entrance(0)) {
                Text(stringResource(R.string.vault_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("IP", ip, { ip = it }, Modifier.weight(2f))
                    Field(stringResource(R.string.port), port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), number = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.hostname_sni), host, { host = it }, Modifier.weight(1f))
                    Field(stringResource(R.string.vault_label), label, { label = it }, Modifier.weight(1f), ltr = false)
                }
                PrimaryButton(onClick = {
                    vm.add(ip.trim(), label, host, port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443); ip = ""; label = ""
                }, enabled = ip.isNotBlank()) { Text(stringResource(R.string.vault_add)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconTextButton(stringResource(R.string.vault_retest_all), AppIconKind.Refresh, { vm.retestAll() }, enabled = entries.isNotEmpty() && busy.isEmpty())
                    if (busy.isNotEmpty()) IconTextButton(stringResource(R.string.cancel), AppIconKind.Stop, { vm.cancel() })
                    IconTextButton(stringResource(R.string.copy_working), AppIconKind.Copy, { copyToClipboard(context, vm.export(true)); snack(copied) })
                    IconTextButton(stringResource(R.string.share), AppIconKind.Share, { shareText(context, vm.export(false)) })
                    IconTextButton(stringResource(R.string.save_file), AppIconKind.Save, { save("scanon-vault", ExportFormat.TXT, vm.export(false)) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VaultSort.entries.forEach { s ->
                        FilterChip(sort == s, { vm.sort.value = s }, label = {
                            Text(when (s) {
                                VaultSort.ADDED -> stringResource(R.string.sort_added)
                                VaultSort.LATENCY -> stringResource(R.string.sort_latency)
                                VaultSort.LABEL -> stringResource(R.string.vault_label)
                                VaultSort.STATUS -> stringResource(R.string.sort_status)
                            })
                        })
                    }
                }
            }
        }
        if (entries.isEmpty()) item(key = "empty") { EmptyState(AppIconKind.Star, stringResource(R.string.vault_empty)) }
        items(entries, key = { it.key }) { e ->
            GlassCard(Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f),
                    placementSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f)).clickable { copyToClipboard(context, e.ip); snack(copied) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (e.lastOk) {
                        true -> AppIcon(AppIconKind.Check, Good, size = 18.dp)
                        false -> AppIcon(AppIconKind.Close, Bad, size = 18.dp)
                        null -> AppIcon(AppIconKind.Info, Neutral, size = 18.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        LtrText(if (e.port == 443) e.ip else "${e.ip} :${e.port}", fontWeight = FontWeight.Bold)
                        if (e.label.isNotEmpty()) Text(e.label, style = MaterialTheme.typography.bodySmall)
                        GeoLine(e.ip)
                    }
                    if (e.key in busy) SpinningRadar(MaterialTheme.colorScheme.primary)
                    else e.lastMs?.let { LtrText("${Fmt.ms(it)} ms", color = latencyColor(it), fontWeight = FontWeight.Bold) }
                }
                LtrText("SNI: ${e.hostname}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Sparkline(e.recent)
                Row {
                    TextButton(onClick = { vm.retest(e) }, enabled = e.key !in busy) { Text(stringResource(R.string.vault_retest)) }
                    TextButton(onClick = { editing = e }) { Text(stringResource(R.string.groups_edit)) }
                    TextButton(onClick = { vm.remove(e); snack(removedMsg) }) { Text(stringResource(R.string.delete)) }
                    TextButton(onClick = { vm.undoRemove() }) { Text(stringResource(R.string.undo)) }
                }
            }
        }
    }
    editing?.let { e ->
        var newLabel by remember(e.key) { mutableStateOf(e.label) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.vault_label)) },
            text = { Field(stringResource(R.string.vault_label), newLabel, { newLabel = it }, ltr = false) },
            confirmButton = { TextButton(onClick = { vm.setLabel(e, newLabel); editing = null }) { Text(stringResource(R.string.save_vault)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun GroupsScreen(vm: GroupsViewModel = viewModel()) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("ip") }
    var items by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<GroupsStore.Group?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "editor") {
            GlassCard(Modifier.entrance(0)) {
                Text(stringResource(R.string.groups_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Field(stringResource(R.string.groups_name), name, { name = it }, Modifier.fillMaxWidth(), ltr = false)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(kind == "ip", { kind = "ip" }, label = { Text(stringResource(R.string.groups_kind_ip)) })
                    FilterChip(kind == "dns", { kind = "dns" }, label = { Text(stringResource(R.string.groups_kind_dns)) })
                }
                Field(stringResource(R.string.groups_items), items, { items = it }, Modifier.fillMaxWidth(), singleLine = false, minLines = 3, maxLines = 8)
                PrimaryButton(onClick = {
                    vm.upsert(name, kind, items.lines()); name = ""; items = ""
                }, enabled = name.isNotBlank() && items.isNotBlank()) { Text(stringResource(R.string.groups_save)) }
            }
        }
        if (groups.isEmpty()) item(key = "empty") { EmptyState(AppIconKind.Folder, stringResource(R.string.groups_empty)) }
        items(groups, key = { it.kind + "|" + it.name }) { g ->
            GlassCard(Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f),
                    placementSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f))) {
                Text("${g.name} · ${if (g.kind == "ip") "IP" else "DNS"} · ${g.items.size}", fontWeight = FontWeight.Bold)
                LtrText(g.items.take(6).joinToString(", ") + if (g.items.size > 6) " …" else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { name = g.name; kind = g.kind; items = g.items.joinToString("\n") }) { Text(stringResource(R.string.groups_edit)) }
                    TextButton(onClick = { renaming = g }) { Text(stringResource(R.string.rename)) }
                    TextButton(onClick = { vm.delete(g) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
    renaming?.let { g ->
        var newName by remember(g.name) { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { Field(stringResource(R.string.groups_name), newName, { newName = it }, ltr = false) },
            confirmButton = { TextButton(onClick = { vm.rename(g, newName); renaming = null }) { Text(stringResource(R.string.save_vault)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val kind by vm.kind.collectAsStateWithLifecycle()
    val copied = stringResource(R.string.copied)
    val removed = stringResource(R.string.removed_undo)
    var confirmClear by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "filters") {
            GlassCard(Modifier.entrance(0)) {
                Field(stringResource(R.string.search_history), query, { vm.query.value = it }, Modifier.fillMaxWidth(), ltr = false)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to stringResource(R.string.all_results), "ip" to stringResource(R.string.nav_ip), "dns" to stringResource(R.string.nav_dns),
                        "domain" to stringResource(R.string.nav_domain), "diag" to stringResource(R.string.nav_diag)).forEach { (k, l) ->
                        FilterChip(kind == k, { vm.kind.value = k }, label = { Text(l) })
                    }
                }
                Row {
                    TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.clear_history)) }
                    TextButton(onClick = { vm.undo() }) { Text(stringResource(R.string.undo)) }
                }
            }
        }
        if (entries.isEmpty()) item(key = "empty") { EmptyState(AppIconKind.Clock, stringResource(R.string.no_scans_yet)) }
        items(entries, key = { it.id }) { e ->
            val open = e.id in expanded
            GlassCard(Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f),
                    placementSpec = androidx.compose.animation.core.spring(stiffness = 300f, dampingRatio = 0.8f)).clickable { expanded = if (open) expanded - e.id else expanded + e.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("#${e.id} · ${e.kind.uppercase()} · ${vm.formatTs(e.ts)}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(e.summary, fontWeight = FontWeight.Bold)
                    }
                    AppIcon(if (open) AppIconKind.ChevronUp else AppIconKind.ChevronDown, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (open) {
                    LtrText(e.body.take(20_000), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { copyToClipboard(context, e.body); snack(copied) }) { Text(stringResource(R.string.copy_all)) }
                        TextButton(onClick = { shareText(context, e.body) }) { Text(stringResource(R.string.share)) }
                        TextButton(onClick = { vm.delete(e); snack(removed) }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.clear_history)) },
        text = { Text(stringResource(R.string.clear_history_confirm)) },
        confirmButton = { TextButton(onClick = { vm.clearAll(); confirmClear = false; snack(removed) }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
