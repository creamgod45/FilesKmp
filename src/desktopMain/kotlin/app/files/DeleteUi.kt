package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * When the Recycle Bin refuses a folder (stage 0) the app offers what works by hand: delete it
 * piece by piece from the inside out — to the Recycle Bin, or for good. After that (stage 1) it
 * lists anything still left with the reason, including which program has it open.
 */
class DeleteReport(val stage: Int, val paths: List<String>, val failures: List<SafeDelete.Failure>)

@Composable
fun DeleteProblemDialog(s: AppState, r: DeleteReport) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(640.dp).heightIn(max = 620.dp).softShadow(shape, 18.dp).background(p.solid, shape).border(1.dp, p.controlStroke, shape).padding(20.dp)
        ) {
            T(if (r.stage == 0) S.recycleRefused else S.deleteLeftovers(r.failures.size), size = 20.sp, weight = FontWeight.SemiBold, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            T(if (r.stage == 0) S.recycleRefusedWhy else S.deleteLeftoversWhy, size = 12.sp, color = p.textSecondary, maxLines = 6)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 300.dp).fillMaxWidth().border(1.dp, p.controlStroke, RoundedCornerShape(4.dp))) {
                if (r.stage == 0) items(r.paths) { path -> T(path, Modifier.padding(10.dp, 5.dp), size = 12.sp) }
                else items(r.failures) { f ->
                    Column(Modifier.padding(10.dp, 5.dp)) {
                        T(f.path, size = 12.sp)
                        T(f.reason, size = 11.sp, color = p.danger, maxLines = 2)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.stage == 0) {
                    DialogButton(S.recycleEach, primary = true) { s.deleteReport = null; s.safeDelete(r.paths, toRecycleBin = true) }
                    DialogButton(S.deleteInsideOut, primary = false) { s.deleteReport = null; s.safeDelete(r.paths, toRecycleBin = false) }
                } else {
                    DialogButton(S.retry, primary = true) { s.deleteReport = null; s.safeDelete(r.failures.map { it.path }.filter { java.io.File(it).exists() }.ifEmpty { r.paths }, toRecycleBin = false) }
                }
                Spacer(Modifier.weight(1f))
                DialogButton(if (r.stage == 0) (if (S.zh) "取消" else "Cancel") else S.close, primary = false) { s.deleteReport = null }
            }
        }
    }
}
