package com.iriver.essentiaanalyzer

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iriver.essentiaanalyzer.model.AnalysisResult
import com.iriver.essentiaanalyzer.model.AnalysisStatus
import com.iriver.essentiaanalyzer.ui.AnalyzerViewModel
import kotlin.math.max

private val SUPPORTED_MIME_TYPES = arrayOf(
  "audio/mpeg",
  "audio/mp4",
  "audio/wav",
  "audio/x-wav",
  "audio/flac",
  "audio/x-flac",
  "application/flac",
  "application/x-flac",
  "audio/*",
)

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          EssentiaAnalyzerApp()
        }
      }
    }
  }
}

@Composable
fun EssentiaAnalyzerApp(
  viewModel: AnalyzerViewModel = viewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  var selectedTab by remember { mutableIntStateOf(0) }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      }
    }

    val displayName = uri?.let {
      context.contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    viewModel.onFileSelected(uri, displayName)
  }

  Column(modifier = Modifier.fillMaxSize()) {
    TabRow(selectedTabIndex = selectedTab) {
      Tab(
        selected = selectedTab == 0,
        onClick = { selectedTab = 0 },
        text = { Text("FileSelectTab") },
      )
      Tab(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
        text = { Text("AnalysisResultTab") },
      )
    }

    when (selectedTab) {
      0 -> FileSelectTab(
        state = state,
        onPickFile = {
          viewModel.onFileSelecting()
          picker.launch(SUPPORTED_MIME_TYPES)
        },
        onAnalyze = { viewModel.analyze(context) },
      )

      else -> AnalysisResultTab(state.result)
    }
  }
}

@Composable
fun FileSelectTab(
  state: com.iriver.essentiaanalyzer.ui.AnalyzerUiState,
  onPickFile: () -> Unit,
  onAnalyze: () -> Unit,
) {
  val isBusy = state.status == AnalysisStatus.Decoding || state.status == AnalysisStatus.Analyzing

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Essentia Native: ${state.nativeInfo}", style = MaterialTheme.typography.bodySmall)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onPickFile) {
        Text("음악 파일 선택")
      }

      Button(
        enabled = state.selectedUri != null && !isBusy,
        onClick = onAnalyze,
      ) {
        Text("분석 시작")
      }
    }

    Text(
      text = "선택 파일: ${state.selectedFileName ?: "없음"}",
      style = MaterialTheme.typography.bodyLarge,
    )

    if (state.decodedAudio != null) {
      Text(
        text = "디코딩 결과: ${"%.2f".format(state.decodedAudio.durationSeconds)}초, " +
          "${state.decodedAudio.sampleRate}Hz, mono",
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    Text(
      text = "상태: ${state.status} / ${state.statusMessage}",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
    )

    state.errorMessage?.let {
      Text(
        text = "오류: $it",
        color = Color(0xFFB3261E),
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    if (isBusy) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text("분석 진행 중...")
      }
    }

    Text(
      text = "입력: SAF(ACTION_OPEN_DOCUMENT) mp3/m4a/wav/flac, 분석: 전체 파일(최대 15분), 샘플레이트: 44.1kHz",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
fun AnalysisResultTab(result: AnalysisResult?) {
  if (result == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("아직 분석 결과가 없습니다")
    }
    return
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      Text("Summary", style = MaterialTheme.typography.titleMedium)
      KeyValueSection(result.summary)
    }

    item {
      Text("Meta", style = MaterialTheme.typography.titleMedium)
      KeyValueSection(result.meta)
    }

    item {
      Text("Charts", style = MaterialTheme.typography.titleMedium)
    }

    item {
      LineChartCard(title = "Waveform Envelope", values = result.series.waveformEnvelope, color = Color(0xFF1976D2))
    }

    item {
      LineChartCard(title = "Spectral Centroid", values = result.series.spectralCentroid, color = Color(0xFFD32F2F))
    }

    item {
      BarChartCard(
        title = "BPM Histogram",
        values = result.series.bpmHistogram.map { it.count },
        labels = result.series.bpmHistogram.map { it.bpm.toInt().toString() },
      )
    }

    item { Text("Temporal", style = MaterialTheme.typography.titleMedium) }
    item { KeyValueSection(result.temporal) }

    item { Text("Spectral", style = MaterialTheme.typography.titleMedium) }
    item { KeyValueSection(result.spectral) }

    item { Text("Rhythm", style = MaterialTheme.typography.titleMedium) }
    item { KeyValueSection(result.rhythm) }

    item { Text("Tonal", style = MaterialTheme.typography.titleMedium) }
    item { KeyValueSection(result.tonal) }

    item { Text("Highlevel", style = MaterialTheme.typography.titleMedium) }
    item { KeyValueSection(result.highlevel) }

    item { Text("Stats", style = MaterialTheme.typography.titleMedium) }
    item { KeyValueSection(result.stats) }

    if (result.errors.isNotEmpty()) {
      item { Text("Errors", style = MaterialTheme.typography.titleMedium, color = Color(0xFFB3261E)) }
      items(result.errors) { err ->
        Text(
          text = "• ${err.algorithm}: ${err.message}",
          color = Color(0xFFB3261E),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun KeyValueSection(map: Map<String, String>) {
  if (map.isEmpty()) {
    Text("(empty)", color = MaterialTheme.colorScheme.onSurfaceVariant)
    return
  }

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    map.forEach { (k, v) ->
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, modifier = Modifier.weight(0.45f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, modifier = Modifier.weight(0.55f))
      }
    }
  }
}

@Composable
private fun LineChartCard(
  title: String,
  values: List<Float>,
  color: Color,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    if (values.isEmpty()) {
      Text("데이터 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
      return
    }

    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(160.dp),
    ) {
      val maxV = values.maxOrNull() ?: 1f
      val minV = values.minOrNull() ?: 0f
      val range = (maxV - minV).takeIf { it > 1e-9f } ?: 1f
      val stepX = size.width / max(1, values.size - 1)

      for (i in 1 until values.size) {
        val x0 = (i - 1) * stepX
        val x1 = i * stepX
        val y0 = size.height - ((values[i - 1] - minV) / range) * size.height
        val y1 = size.height - ((values[i] - minV) / range) * size.height
        drawLine(color = color, start = Offset(x0, y0), end = Offset(x1, y1), strokeWidth = 2.5f)
      }
    }
  }
}

@Composable
private fun BarChartCard(
  title: String,
  values: List<Float>,
  labels: List<String>,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    if (values.isEmpty()) {
      Text("데이터 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
      return
    }

    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(180.dp),
    ) {
      val maxV = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
      val barWidth = size.width / max(1, values.size)

      values.forEachIndexed { idx, v ->
        val left = idx * barWidth + 2f
        val right = (idx + 1) * barWidth - 2f
        val top = size.height - (v / maxV) * size.height
        drawRect(
          color = Color(0xFF388E3C),
          topLeft = Offset(left, top),
          size = androidx.compose.ui.geometry.Size(right - left, size.height - top),
        )
      }
    }

    Text(
      text = "Bins: ${labels.take(12).joinToString()}${if (labels.size > 12) " ..." else ""}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
