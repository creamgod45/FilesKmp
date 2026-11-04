package app.files

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// Categorical series colours (validated: scripts/validate_palette.js, light on #f7f7f7, dark on #2a2a2a).
// Light slots 2-4 are under 3:1 against the card, so every line is direct-labelled and the tiles list the values.
private val SERIES_LIGHT = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100))
private val SERIES_DARK = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500))
/** The chart counts in GiB, the unit Fs.sizeText shows in the tiles. */
private const val GB = 1024.0 * 1024 * 1024

private enum class StorageRange(val ms: Long?, val label: () -> String) {
    Day(24L * 3600_000, { S.range24h }), Week(7L * 24 * 3600_000, { S.range7d }),
    Month(30L * 24 * 3600_000, { S.range30d }), All(null, { S.rangeAll }),
}

/** Settings → 儲存空間: the recording switch, the growth chart, the user folder, clean-up suggestions. */
@Composable
fun StorageSettings(s: AppState) {
    val p = LocalPalette.current
    ToggleCard(S.trackStorage, s.trackStorage) { s.trackStorage = it }
    T(S.trackStorageInfo, Modifier.padding(horizontal = 4.dp, vertical = 4.dp), size = 12.sp, color = p.textSecondary, maxLines = 3)
    T(S.storageOnHome, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), size = 12.sp, color = p.textSecondary, maxLines = 2)
    GroupTitle(S.cleanupTitle)
    CleanupSection(s)
}

// ------------------------------------------------------------------ Home widget

/**
 * Home → 儲存空間分析 (open by default, collapsible like the other widgets). It never competes with
 * the rest of Home: it shows a skeleton, waits until Quick access, Drives and Recent have loaded,
 * then reads the history and sizes the caches in the background. Collapsed, it loads nothing.
 */
@Composable
fun HomeStorageWidget(s: AppState) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) {
        snapshotFlow { s.pinnedLoaded && s.drivesLoaded && s.recentLoaded }.first { it }
        kotlinx.coroutines.delay(M.STORAGE_WIDGET_DELAY_MS)
        if (System.getProperty("fileskmp.noStorageLoad") == null) s.loadStorageOverview() // property: tests only (keeps the skeleton)
    }
    val ready = s.cleanupResults != null && (!s.trackStorage || s.storageHistoryLoaded)
    if (!ready) { StorageSkeleton(); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.storageReminder?.let { msg -> ReminderBanner(msg) }
        if (s.trackStorage) GrowthSection(s)
        else Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(S.trackStorage, weight = FontWeight.SemiBold)
                    T(S.trackStorageInfo, size = 12.sp, color = p.textSecondary, maxLines = 3)
                }
                Spacer(Modifier.width(12.dp))
                Switch(s.trackStorage, { s.trackStorage = it })
            }
        }
        CleanupSummary(s)
        ProfileSection(s)
    }
}

@Composable
private fun ReminderBanner(msg: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card)
            .border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status, not a series: icon + text, never colour alone.
        Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = Color(0xFFC98500))
        Spacer(Modifier.width(10.dp))
        T(msg, Modifier.weight(1f), maxLines = 2)
    }
}

/** The biggest clean-up suggestions; actions and the full list are in 設定 → 儲存空間. */
@Composable
private fun CleanupSummary(s: AppState) {
    val p = LocalPalette.current
    val results = s.cleanupResults.orEmpty()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp), tint = p.text)
            Spacer(Modifier.width(10.dp))
            T("${S.cleanupTitle}: ${Fs.sizeText(results.sumOf { it.bytes })}", Modifier.weight(1f), weight = FontWeight.SemiBold)
            SmallAction(Icons.Outlined.Refresh, if (s.cleanupScanning) S.cleanupScanning else S.cleanupScan, enabled = !s.cleanupScanning) { s.scanCleanup() }
            SmallAction(Icons.Outlined.Storage, S.viewAllCleanup) { s.settingsStorageRequest = true; s.settingsOpen = true }
        }
        Spacer(Modifier.height(6.dp))
        // Magnitude: one hue, bars from a shared baseline, largest first.
        val shown = results.take(M.STORAGE_WIDGET_ROWS)
        val max = shown.firstOrNull()?.bytes?.coerceAtLeast(1) ?: 1
        shown.forEach { r ->
            Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(4.dp))
                .clickable { s.settingsStorageRequest = true; s.settingsOpen = true },
                verticalAlignment = Alignment.CenterVertically) {
                T(r.target.name, Modifier.width(170.dp), size = 12.sp)
                T(if (r.target.audience == CleanupAudience.General) S.cleanupGeneral else S.cleanupDeveloper,
                    Modifier.width(56.dp), size = 11.sp, color = p.textTertiary)
                Box(Modifier.weight(1f).height(10.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((r.bytes.toFloat() / max).coerceIn(0.005f, 1f))
                        .background(if (p.dark) SERIES_DARK[0] else SERIES_LIGHT[0], RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
                }
                T(Fs.sizeText(r.bytes), Modifier.width(90.dp).padding(start = 8.dp), size = 12.sp, color = p.textSecondary)
            }
        }
    }
}

/** Placeholder in the widget's own shape while it waits its turn. */
@Composable
private fun StorageSkeleton() {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition()
    val alpha by pulse.animateFloat(
        0.08f, 0.18f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
    )
    val p = LocalPalette.current
    @Composable fun Bone(m: Modifier) = Box(m.clip(RoundedCornerShape(6.dp)).background(p.text.copy(alpha = alpha)))
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(4) { Bone(Modifier.weight(1f).height(64.dp)) } }
        Spacer(Modifier.height(12.dp))
        Bone(Modifier.fillMaxWidth().height(140.dp))
        Spacer(Modifier.height(12.dp))
        repeat(4) { i -> Bone(Modifier.fillMaxWidth(1f - i * 0.15f).height(14.dp)); Spacer(Modifier.height(8.dp)) }
    }
}

// ------------------------------------------------------------------ growth chart

@Composable
private fun GrowthSection(s: AppState) {
    val p = LocalPalette.current
    var range by remember { mutableStateOf(StorageRange.Week) }
    val drives = s.storageSamples.filter { it.key != PROFILE_KEY }
    // Colour follows the drive (sorted by letter over the whole history), never its rank in the current range.
    val keys = drives.map { it.key }.distinct().sorted()
    val colors = if (p.dark) SERIES_DARK else SERIES_LIGHT
    fun colorOf(key: String) = colors[keys.indexOf(key).coerceAtLeast(0) % colors.size]
    val now = System.currentTimeMillis()
    val inRange = drives.filter { range.ms == null || it.time >= now - range.ms!! }
    val series = keys.mapNotNull { k -> inRange.filter { it.key == k }.sortedBy { it.time }.takeIf { it.isNotEmpty() }?.let { k to it } }

    Card {
        // Filters in one row above the chart.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StorageRange.entries.forEach { r -> Chip(r.label(), r == range) { range = r } }
        }
        Spacer(Modifier.height(12.dp))
        // Stat tiles: the exact numbers (also the table view for the chart).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            series.forEach { (k, list) ->
                val last = list.last()
                val delta = last.used - list.first().used
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(colorOf(k), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        T(k, size = 12.sp, color = p.textSecondary)
                    }
                    T((if (delta >= 0) "+" else "−") + Fs.sizeText(abs(delta)), size = 18.sp, weight = FontWeight.SemiBold)
                    T("${Fs.sizeText(last.used)} ${S.storageUsedOf} ${Fs.sizeText(last.total)}", size = 11.sp, color = p.textTertiary, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (series.none { it.second.size >= 2 }) {
            T(S.storageNeedMore, Modifier.padding(vertical = 24.dp), color = p.textSecondary, maxLines = 2)
        } else {
            GrowthChart(series, ::colorOf)
        }
    }
}

/**
 * Line chart, one line per drive: change in used space (GB) since the first reading in the
 * range, so drives of any size share one axis. Hover shows a crosshair and every drive's value.
 */
@Composable
private fun GrowthChart(series: List<Pair<String, List<StorageSample>>>, colorOf: (String) -> Color) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val points = series.map { (k, list) -> k to list.map { it.time to (it.used - list.first().used) / GB } }
    val tMin = points.minOf { it.second.first().first }
    val tMax = points.maxOf { it.second.last().first }.coerceAtLeast(tMin + 1)
    val vMin = minOf(0.0, points.minOf { pts -> pts.second.minOf { it.second } })
    val vMax = maxOf(0.0, points.maxOf { pts -> pts.second.maxOf { it.second } })
    val ticks = niceTicks(vMin, vMax)
    val labelStyle = TextStyle(color = p.textSecondary, fontSize = 11.sp)
    val dateFmt = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())

    T(S.storageGrowthAxis, Modifier.padding(bottom = 6.dp), size = 12.sp, color = p.textSecondary)
    // Legend (always, for 2+ series); lines are also labelled at their ends.
    if (points.size >= 2) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        points.forEach { (k, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 2.dp).background(colorOf(k)))
                Spacer(Modifier.width(6.dp))
                T(k, size = 12.sp, color = p.textSecondary)
            }
        }
    }
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    hoverX = if (e.type == PointerEventType.Exit) null else e.changes.firstOrNull()?.position?.x
                }
            }
        }
    ) {
        val left = 56f; val right = size.width - 48f; val top = 8f; val bottom = size.height - 24f
        fun x(t: Long) = left + (right - left) * ((t - tMin).toFloat() / (tMax - tMin))
        fun y(v: Double) = bottom - (bottom - top) * ((v - ticks.first()) / (ticks.last() - ticks.first())).toFloat()
        // Recessive grid and y labels; the zero line a little stronger.
        ticks.forEach { v ->
            val yy = y(v)
            drawLine(if (v == 0.0) p.textTertiary else p.divider, Offset(left, yy), Offset(right, yy), strokeWidth = 1f)
            val label = measurer.measure(gbLabel(v), labelStyle)
            drawText(label, topLeft = Offset(left - 8f - label.size.width, yy - label.size.height / 2f))
        }
        listOf(tMin, tMax).forEach { t ->
            val label = measurer.measure(dateFmt.format(Instant.ofEpochMilli(t)), labelStyle)
            val lx = if (t == tMin) left else right - label.size.width
            drawText(label, topLeft = Offset(lx, bottom + 6f))
        }
        // Direct labels at the newest readings, nudged apart so close lines don't overlap.
        val labels = points.map { (k, pts) -> Triple(k, measurer.measure(k, labelStyle.copy(color = p.text)), y(pts.last().second)) }
            .sortedBy { it.third }
        val placed = ArrayList<Pair<Triple<String, androidx.compose.ui.text.TextLayoutResult, Float>, Float>>()
        labels.forEach { l ->
            val h = l.second.size.height.toFloat()
            val want = l.third - h / 2f
            val min = placed.lastOrNull()?.let { it.second + it.first.second.size.height } ?: Float.NEGATIVE_INFINITY
            placed += l to maxOf(want, min)
        }
        // Lines (2 px, round joins) with a marker at the newest reading.
        points.forEach { (k, pts) ->
            val c = colorOf(k)
            if (pts.size >= 2) {
                val path = Path().apply {
                    pts.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) }
                }
                drawPath(path, c, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val (lt, lv) = pts.last()
            drawCircle(p.solid, radius = 6f, center = Offset(x(lt), y(lv)))
            drawCircle(c, radius = 4.5f, center = Offset(x(lt), y(lv)))
        }
        placed.forEach { (l, top) -> drawText(l.second, topLeft = Offset(right + 10f, top)) }
        // Hover: crosshair + a tooltip with each drive's value at the nearest earlier reading.
        hoverX?.takeIf { it in left..right }?.let { hx ->
            val t = tMin + ((hx - left) / (right - left) * (tMax - tMin)).toLong()
            drawLine(p.textTertiary, Offset(hx, top), Offset(hx, bottom), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            val rows = points.mapNotNull { (k, pts) -> pts.lastOrNull { it.first <= t }?.let { k to it.second } }
            if (rows.isNotEmpty()) {
                val lines = listOf(dateFmt.format(Instant.ofEpochMilli(t))) + rows.map { (k, v) -> "$k  ${gbLabel(v, precise = true)}" }
                val measured = lines.mapIndexed { i, l -> measurer.measure(l, labelStyle.copy(color = if (i == 0) p.textSecondary else p.text, fontSize = 12.sp)) }
                val w = measured.maxOf { it.size.width } + 24f + 10f
                val h = measured.sumOf { it.size.height } + 16f
                val bx = if (hx + 12f + w > size.width) hx - 12f - w else hx + 12f
                drawRoundRect(p.flyout, topLeft = Offset(bx, top), size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f))
                drawRoundRect(p.controlStroke, topLeft = Offset(bx, top), size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f), style = Stroke(1f))
                var ty = top + 8f
                measured.forEachIndexed { i, m ->
                    if (i > 0) drawCircle(colorOf(rows[i - 1].first), radius = 4f, center = Offset(bx + 14f, ty + m.size.height / 2f))
                    drawText(m, topLeft = Offset(bx + if (i == 0) 12f else 24f, ty))
                    ty += m.size.height
                }
            }
        }
    }
}

/** 4-6 round tick values covering [lo, hi] (always including 0). */
private fun niceTicks(lo: Double, hi: Double): List<Double> {
    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
    val raw = span / 4
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.toList()
}

private fun gbLabel(v: Double, precise: Boolean = false): String {
    val sign = if (v > 0) "+" else if (v < 0) "−" else ""
    val a = abs(v)
    val num = if (precise || a < 10) "%.2f".format(a).trimEnd('0').trimEnd('.') else "%.0f".format(a)
    return "$sign$num GiB"
}

// ------------------------------------------------------------------ user folder

@Composable
private fun ProfileSection(s: AppState) {
    val p = LocalPalette.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(userHome, weight = FontWeight.SemiBold)
                T(S.storageProfileInfo, size = 12.sp, color = p.textSecondary, maxLines = 2)
            }
            val progress = s.profileProgress
            DialogButton(if (progress != null) "${S.storageAnalyzing} ${progress.first}/${progress.second}" else S.storageAnalyze, primary = progress == null) {
                s.analyzeProfile()
            }
        }
        val entries = s.profileEntries
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val total = entries.sumOf { it.bytes }
            T("${S.storageProfile}: ${Fs.sizeText(total)}", size = 13.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            // Magnitude: one hue, bars from a shared baseline, largest first.
            val max = entries.first().bytes.coerceAtLeast(1)
            val shown = entries.take(15)
            shown.forEach { e ->
                Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(4.dp)).clickable { s.settingsOpen = false; s.navigate(e.path) },
                    verticalAlignment = Alignment.CenterVertically) {
                    T(e.name, Modifier.width(180.dp), size = 12.sp)
                    Box(Modifier.weight(1f).height(10.dp)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((e.bytes.toFloat() / max).coerceIn(0.005f, 1f))
                            .background(if (p.dark) SERIES_DARK[0] else SERIES_LIGHT[0], RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
                    }
                    T(Fs.sizeText(e.bytes), Modifier.width(90.dp).padding(start = 8.dp), size = 12.sp, color = p.textSecondary)
                }
            }
            if (entries.size > shown.size) {
                T((if (S.zh) "其他 ${entries.size - shown.size} 項：" else "${entries.size - shown.size} more: ") + Fs.sizeText(entries.drop(shown.size).sumOf { it.bytes }),
                    Modifier.padding(top = 4.dp), size = 12.sp, color = p.textTertiary)
            }
        }
    }
}

// ------------------------------------------------------------------ clean-up suggestions

@Composable
private fun CleanupSection(s: AppState) {
    val p = LocalPalette.current
    var confirm by remember { mutableStateOf<CleanupTarget?>(null) }
    T(S.cleanupInfo, Modifier.padding(horizontal = 4.dp, vertical = 4.dp), size = 12.sp, color = p.textSecondary, maxLines = 3)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp), tint = p.text)
            Spacer(Modifier.width(10.dp))
            T(S.windowsTools, Modifier.weight(1f), weight = FontWeight.SemiBold)
            DialogButton(S.diskCleanup, primary = false) { Cleanup.openDiskCleanup() }
            Spacer(Modifier.width(8.dp))
            DialogButton(S.storageSense, primary = false) { Cleanup.openStorageSense() }
        }
    }
    Spacer(Modifier.height(4.dp))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Storage, null, Modifier.size(18.dp), tint = p.text)
            Spacer(Modifier.width(10.dp))
            val results = s.cleanupResults
            T(if (results == null) S.cleanupTitle else "${S.cleanupTitle}: ${Fs.sizeText(results.sumOf { it.bytes })}",
                Modifier.weight(1f), weight = FontWeight.SemiBold)
            DialogButton(if (s.cleanupScanning) S.cleanupScanning else S.cleanupScan, primary = results == null && !s.cleanupScanning) { s.scanCleanup() }
        }
    }
    s.cleanupResults?.forEach { r -> CleanupRow(s, r) { confirm = r.target } }
    confirm?.let { t -> RunConfirmDialog(t, onDismiss = { confirm = null }) { Cleanup.runInTerminal(t.command!!); confirm = null } }
    s.cleanPlan?.let { plan -> CleanPlanDialog(plan, onDismiss = { s.cleanPlan = null }) { s.runCleanPlan() } }
}

@Composable
private fun CleanupRow(s: AppState, r: CleanupResult, onRun: () -> Unit) {
    val p = LocalPalette.current
    val t = r.target
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(p.card)
            .border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(t.name, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            T(if (t.audience == CleanupAudience.General) S.cleanupGeneral else S.cleanupDeveloper,
                Modifier.border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                size = 11.sp, color = p.textSecondary)
            Spacer(Modifier.weight(1f))
            T(Fs.sizeText(r.bytes), size = 16.sp, weight = FontWeight.SemiBold)
        }
        T(t.description, Modifier.padding(top = 2.dp), size = 12.sp, color = p.textSecondary, maxLines = 4)
        T(t.paths.filter { it.exists() }.joinToString("  ·  ") { it.path }, Modifier.padding(top = 2.dp), size = 11.sp, color = p.textTertiary, maxLines = 2)
        r.details.forEach { T("•  $it", Modifier.padding(start = 4.dp), size = 12.sp, color = p.textSecondary) }
        t.command?.let { cmd ->
            T(cmd + if (r.toolMissing) "   (${t.tool} ${S.toolMissing})" else "",
                Modifier.padding(top = 6.dp).fillMaxWidth().background(p.control, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                size = 12.sp, color = p.text, maxLines = 3, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallAction(Icons.Outlined.FolderOpen, S.openFolder) { t.paths.firstOrNull { it.exists() }?.let { Fs.open(it.path) } }
            t.command?.let { cmd ->
                SmallAction(Icons.Outlined.ContentCopy, S.copyCommand) { copyText(cmd) }
                SmallAction(Icons.Outlined.PlayArrow, S.runCommand, enabled = !r.toolMissing) { onRun() }
            }
            if (t.safeDelete != null) {
                val busy = s.cleanPlanning == t.id
                SmallAction(Icons.Outlined.CleaningServices, if (busy) S.previewing else S.previewClean, enabled = !busy && !s.cleaning && r.bytes > 0) {
                    s.planCleanup(t)
                }
            }
        }
    }
}

@Composable
private fun RunConfirmDialog(t: CleanupTarget, onDismiss: () -> Unit, onRun: () -> Unit) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Popup(alignment = Alignment.Center, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Column(
            Modifier.width(460.dp).shadow8().clip(RoundedCornerShape(8.dp)).background(p.solid)
                .border(1.dp, p.controlStroke, RoundedCornerShape(8.dp)).padding(20.dp)
        ) {
            T(S.runConfirmTitle, size = 18.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            T("${t.name} — ${S.runConfirmText}", color = p.textSecondary, maxLines = 2)
            T(t.command ?: "", Modifier.padding(vertical = 8.dp).fillMaxWidth().background(p.control, RoundedCornerShape(4.dp)).padding(8.dp),
                size = 12.sp, maxLines = 6, fontFamily = FontFamily.Monospace)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton(S.cancel, primary = false, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                DialogButton(S.run, primary = true, onClick = onRun)
            }
        }
    }
}

/** SafeCleaner's preview: what will go, what stays, and the rules; nothing happens until 移除. */
@Composable
private fun CleanPlanDialog(plan: CleanPlan, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Popup(alignment = Alignment.Center, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Column(
            Modifier.width(560.dp).shadow8().clip(RoundedCornerShape(8.dp)).background(p.solid)
                .border(1.dp, p.controlStroke, RoundedCornerShape(8.dp)).padding(20.dp)
        ) {
            T(S.cleanConfirmTitle, size = 18.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            T(plan.target.name, color = p.textSecondary)
            Spacer(Modifier.height(10.dp))
            if (plan.files.isEmpty()) T(S.cleanNothing, weight = FontWeight.SemiBold)
            else T(S.cleanSummary(plan.files.size, Fs.sizeText(plan.bytes)), size = 16.sp, weight = FontWeight.SemiBold)
            T(S.cleanSkipped(plan.skippedLinks, plan.skippedRecent), size = 12.sp, color = p.textSecondary, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            T(S.cleanRules, size = 12.sp, color = p.textSecondary, maxLines = 4)
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth().background(p.control, RoundedCornerShape(4.dp)).padding(8.dp)) {
                plan.target.safeDelete?.roots.orEmpty().filter { it.exists() }.forEach {
                    T(it.path, size = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                }
                if (plan.files.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    plan.files.take(5).forEach { T("  " + it.toString(), size = 11.sp, color = p.textTertiary, fontFamily = FontFamily.Monospace) }
                    if (plan.files.size > 5) T("  …", size = 11.sp, color = p.textTertiary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton(S.cancel, primary = false, onClick = onDismiss)
                if (plan.files.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    DialogButton(S.clean, primary = true, onClick = onConfirm)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    T(text,
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) p.accent else p.control)
            .border(1.dp, if (selected) p.accent else p.controlStroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        size = 12.sp, color = if (selected) Color.White else p.text)
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Hover(Modifier.height(28.dp), onClick = onClick, enabled = enabled) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = if (enabled) p.text else p.textTertiary)
            Spacer(Modifier.width(6.dp))
            T(text, size = 12.sp, color = if (enabled) p.text else p.textTertiary)
        }
    }
}
