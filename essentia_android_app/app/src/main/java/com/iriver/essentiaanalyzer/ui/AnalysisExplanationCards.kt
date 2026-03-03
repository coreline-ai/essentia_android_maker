package com.iriver.essentiaanalyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iriver.essentiaanalyzer.model.AnalysisResult
import com.iriver.essentiaanalyzer.model.isFatalAnalysisError
import java.util.Locale

data class KoreanAnalysisExplanation(
  val summary: String,
  val metricInsights: List<String>,
  val cautions: List<String>,
)

@Composable
fun AnalysisExplanationCards(
  result: AnalysisResult,
  modifier: Modifier = Modifier,
) {
  val explanation = remember(result) { buildKoreanAnalysisExplanation(result) }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    ExplanationCard(
      title = "한글 결과 요약 (Summary)",
      body = explanation.summary,
      testTag = "analysis_explanation_summary",
    )
    ExplanationCard(
      title = "지표 해석 (Metrics)",
      bullets = explanation.metricInsights,
      testTag = "analysis_explanation_metrics",
    )
    ExplanationCard(
      title = "주의 포인트 (Cautions)",
      bullets = explanation.cautions,
      testTag = "analysis_explanation_cautions",
    )
  }
}

fun buildKoreanAnalysisExplanation(result: AnalysisResult): KoreanAnalysisExplanation {
  val bpm = numberFrom(result.summary, "BPM") ?: numberFrom(result.rhythm, "bpm")
  val key = result.summary["Key"]?.takeIf { it.isNotBlank() }
  val loudness = numberFrom(result.summary, "Loudness") ?: numberFrom(result.temporal, "loudness")
  val duration = numberFrom(result.temporal, "duration")
  val onsetRate = numberFrom(result.temporal, "onsetRate")
  val spectralCentroid = numberFrom(result.spectral, "spectralCentroidMean")
    ?: numberFrom(result.summary, "SpectralCentroid")
  val danceability = numberFrom(result.highlevel, "danceability")
  val confidence = numberFrom(result.rhythm, "confidence")
  val rms = numberFrom(result.summary, "RMS") ?: numberFrom(result.stats, "rms")

  val summaryParts = mutableListOf<String>()
  if (bpm != null) {
    summaryParts += "평균 템포는 약 ${formatValue(bpm, 1)} BPM이며 ${describeTempo(bpm)}입니다."
  } else {
    summaryParts += "평균 템포(BPM)를 계산하지 못해 박자 기반 해석은 제한적입니다."
  }
  if (key != null) {
    summaryParts += "주요 조성은 $key 로 추정됩니다."
  }
  if (loudness != null) {
    summaryParts += "전체 라우드니스는 ${formatValue(loudness, 2)} dB 수준입니다."
  }
  if (duration != null) {
    summaryParts += "분석 길이는 ${formatValue(duration, 1)}초입니다."
  }

  val metricInsights = mutableListOf<String>()
  if (onsetRate != null) {
    metricInsights += "온셋 빈도는 ${formatValue(onsetRate, 2)}회/초로 ${describeOnsetRate(onsetRate)}입니다."
  }
  if (spectralCentroid != null) {
    metricInsights += "스펙트럴 센트로이드는 ${formatValue(spectralCentroid, 0)}Hz로 ${describeCentroid(spectralCentroid)} 성향입니다."
  }
  if (danceability != null) {
    metricInsights += "Danceability ${formatValue(danceability, 2)}로 ${describeDanceability(danceability)} 수준입니다."
  }
  if (confidence != null) {
    metricInsights += "리듬 신뢰도는 ${formatValue(confidence, 2)}로 ${describeRhythmConfidence(confidence)}입니다."
  }
  if (rms != null) {
    metricInsights += "RMS ${formatValue(rms, 4)}로 평균 에너지 크기는 ${describeRms(rms)}입니다."
  }
  if (metricInsights.isEmpty()) {
    metricInsights += "지표 값이 부족해 세부 해석을 생성하지 못했습니다."
  }

  val fatalErrors = result.errors.filter(::isFatalAnalysisError)
  val warningErrors = result.errors.filterNot(::isFatalAnalysisError)
  val cautions = mutableListOf<String>()
  if (fatalErrors.isNotEmpty()) {
    cautions += "FATAL: 치명 오류 ${fatalErrors.size}건이 보고되었습니다. 일부 지표는 신뢰하기 어렵습니다."
  }
  if (warningErrors.isNotEmpty()) {
    cautions += "WARN: 경고 ${warningErrors.size}건이 보고되었습니다. 오류 상세를 함께 확인하세요."
  }
  if (confidence != null && confidence < 1.0) {
    cautions += "리듬 신뢰도가 낮아 BPM/비트 추정 오차가 클 수 있습니다."
  }
  if (duration != null && duration < 15.0) {
    cautions += "분석 길이가 짧아 통계 지표의 안정성이 떨어질 수 있습니다."
  }
  if (bpm == null || spectralCentroid == null) {
    cautions += "핵심 지표 일부가 비어 있어 결과 해석이 제한됩니다."
  }
  if (cautions.isEmpty()) {
    cautions += "현재 결과에서 치명 오류나 뚜렷한 경고는 없습니다."
  }

  return KoreanAnalysisExplanation(
    summary = summaryParts.joinToString(" "),
    metricInsights = metricInsights,
    cautions = cautions,
  )
}

@Composable
private fun ExplanationCard(
  title: String,
  body: String? = null,
  bullets: List<String> = emptyList(),
  testTag: String,
) {
  ElevatedCard(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(testTag),
    colors = CardDefaults.elevatedCardColors(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      if (!body.isNullOrBlank()) {
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      bullets.forEachIndexed { index, message ->
        Text(
          text = "${index + 1}. $message",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

private fun numberFrom(map: Map<String, String>, key: String): Double? {
  return map[key]?.trim()?.toDoubleOrNull()
}

private fun formatValue(value: Double, digits: Int): String {
  return String.format(Locale.US, "%.${digits}f", value)
}

private fun describeTempo(bpm: Double): String {
  return when {
    bpm < 60.0 -> "매우 느린 템포"
    bpm < 90.0 -> "느린 템포"
    bpm < 120.0 -> "중간 템포"
    bpm < 140.0 -> "빠른 템포"
    else -> "매우 빠른 템포"
  }
}

private fun describeOnsetRate(onsetRate: Double): String {
  return when {
    onsetRate < 1.0 -> "이벤트 변화가 적은 편"
    onsetRate < 3.0 -> "리듬 이벤트가 적당한 편"
    else -> "리듬 이벤트가 매우 촘촘한 편"
  }
}

private fun describeCentroid(centroid: Double): String {
  return when {
    centroid < 1500.0 -> "저중역 중심의 어두운"
    centroid < 3000.0 -> "중역이 균형 잡힌"
    else -> "고역 비중이 큰 밝은"
  }
}

private fun describeDanceability(danceability: Double): String {
  return when {
    danceability < 0.40 -> "낮은"
    danceability < 0.70 -> "보통"
    else -> "높은"
  }
}

private fun describeRhythmConfidence(confidence: Double): String {
  return when {
    confidence < 1.0 -> "낮은 수준"
    confidence < 3.0 -> "중간 수준"
    else -> "높은 수준"
  }
}

private fun describeRms(rms: Double): String {
  return when {
    rms < 0.03 -> "낮은 편"
    rms < 0.12 -> "보통 수준"
    else -> "큰 편"
  }
}
