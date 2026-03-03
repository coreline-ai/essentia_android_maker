package com.iriver.essentiaanalyzer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.iriver.essentiaanalyzer.model.AnalysisError
import com.iriver.essentiaanalyzer.model.AnalysisResult
import com.iriver.essentiaanalyzer.model.AnalysisSeries

class AnalysisExplanationTestActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        AnalysisExplanationCards(result = sampleResultForUiTest())
      }
    }
  }
}

private fun sampleResultForUiTest(): AnalysisResult {
  return AnalysisResult(
    rawJson = "{}",
    meta = mapOf("durationSeconds" to "30.0"),
    summary = mapOf(
      "BPM" to "128.0",
      "Key" to "C major",
      "Loudness" to "-9.4",
      "RMS" to "0.1200",
      "SpectralCentroid" to "2450.0",
    ),
    temporal = mapOf(
      "duration" to "30.0",
      "onsetRate" to "2.4",
    ),
    spectral = mapOf("spectralCentroidMean" to "2450.0"),
    rhythm = mapOf(
      "bpm" to "128.0",
      "confidence" to "2.6",
    ),
    tonal = emptyMap(),
    highlevel = mapOf("danceability" to "0.81"),
    stats = mapOf("rms" to "0.1200"),
    series = AnalysisSeries(),
    errors = listOf(
      AnalysisError(algorithm = "native", message = "fatal native failure"),
      AnalysisError(algorithm = "RhythmExtractor", message = "low confidence"),
    ),
  )
}
