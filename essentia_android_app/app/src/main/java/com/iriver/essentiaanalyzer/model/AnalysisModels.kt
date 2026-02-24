package com.iriver.essentiaanalyzer.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class AnalysisStatus {
  Idle,
  Picking,
  Decoding,
  Analyzing,
  Done,
  Error,
}

data class BpmHistogramEntry(
  val bpm: Float,
  val count: Float,
)

data class AnalysisSeries(
  val waveformEnvelope: List<Float> = emptyList(),
  val spectralCentroid: List<Float> = emptyList(),
  val onsetStrength: List<Float> = emptyList(),
  val bpmHistogram: List<BpmHistogramEntry> = emptyList(),
)

data class AnalysisError(
  val algorithm: String,
  val message: String,
)

data class AnalysisResult(
  val rawJson: String,
  val meta: Map<String, String>,
  val summary: Map<String, String>,
  val temporal: Map<String, String>,
  val spectral: Map<String, String>,
  val rhythm: Map<String, String>,
  val tonal: Map<String, String>,
  val highlevel: Map<String, String>,
  val stats: Map<String, String>,
  val series: AnalysisSeries,
  val errors: List<AnalysisError>,
)

object AnalysisJsonParser {
  fun parse(rawJson: String): AnalysisResult {
    val root = JSONObject(rawJson)

    return AnalysisResult(
      rawJson = rawJson,
      meta = root.optJSONObject("meta").toDisplayMap(),
      summary = root.optJSONObject("summary").toDisplayMap(),
      temporal = root.optJSONObject("temporal").toDisplayMap(),
      spectral = root.optJSONObject("spectral").toDisplayMap(),
      rhythm = root.optJSONObject("rhythm").toDisplayMap(),
      tonal = root.optJSONObject("tonal").toDisplayMap(),
      highlevel = root.optJSONObject("highlevel").toDisplayMap(),
      stats = root.optJSONObject("stats").toDisplayMap(),
      series = parseSeries(root.optJSONObject("series")),
      errors = parseErrors(root.optJSONArray("errors")),
    )
  }

  private fun JSONObject?.toDisplayMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val result = linkedMapOf<String, String>()
    val iterator = keys()
    while (iterator.hasNext()) {
      val key = iterator.next()
      result[key] = valueToReadableString(opt(key))
    }
    return result
  }

  private fun valueToReadableString(value: Any?): String {
    return when (value) {
      null -> "null"
      is Number -> formatNumber(value.toDouble())
      is String -> value
      is JSONArray -> {
        if (value.length() == 0) "[]" else "[${value.length()} values]"
      }
      is JSONObject -> "{...}"
      else -> value.toString()
    }
  }

  private fun formatNumber(value: Double): String {
    if (!value.isFinite()) return "null"
    return String.format(Locale.US, "%.4f", value)
      .trimEnd('0')
      .trimEnd('.')
      .ifBlank { "0" }
  }

  private fun parseSeries(seriesObj: JSONObject?): AnalysisSeries {
    if (seriesObj == null) return AnalysisSeries()

    return AnalysisSeries(
      waveformEnvelope = parseFloatArray(seriesObj.optJSONArray("waveformEnvelope")),
      spectralCentroid = parseFloatArray(seriesObj.optJSONArray("spectralCentroid")),
      onsetStrength = parseFloatArray(seriesObj.optJSONArray("onsetStrength")),
      bpmHistogram = parseBpmHistogram(seriesObj.optJSONArray("bpmHistogram")),
    )
  }

  private fun parseFloatArray(array: JSONArray?): List<Float> {
    if (array == null) return emptyList()
    val out = ArrayList<Float>(array.length())
    for (i in 0 until array.length()) {
      val value = array.optDouble(i, Double.NaN)
      if (value.isFinite()) out.add(value.toFloat())
    }
    return out
  }

  private fun parseBpmHistogram(array: JSONArray?): List<BpmHistogramEntry> {
    if (array == null) return emptyList()
    val out = ArrayList<BpmHistogramEntry>(array.length())
    for (i in 0 until array.length()) {
      val obj = array.optJSONObject(i) ?: continue
      val bpm = obj.optDouble("bpm", Double.NaN)
      val count = obj.optDouble("count", Double.NaN)
      if (bpm.isFinite() && count.isFinite()) {
        out.add(BpmHistogramEntry(bpm.toFloat(), count.toFloat()))
      }
    }
    return out
  }

  private fun parseErrors(array: JSONArray?): List<AnalysisError> {
    if (array == null) return emptyList()
    val out = ArrayList<AnalysisError>(array.length())
    for (i in 0 until array.length()) {
      val obj = array.optJSONObject(i) ?: continue
      out.add(
        AnalysisError(
          algorithm = obj.optString("algorithm", "unknown"),
          message = obj.optString("message", "unknown error"),
        )
      )
    }
    return out
  }
}
