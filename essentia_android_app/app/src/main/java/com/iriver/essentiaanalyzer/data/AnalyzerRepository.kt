package com.iriver.essentiaanalyzer.data

import android.content.Context
import android.net.Uri
import com.iriver.essentiaanalyzer.model.AnalysisJsonParser
import com.iriver.essentiaanalyzer.model.AnalysisResult
import com.iriver.essentiaanalyzer.nativebridge.EssentiaNativeBridge

class AnalyzerRepository(
  private val decoder: AudioDecoder = AudioDecoder(),
  private val resampler: LinearResampler = LinearResampler(),
) : AnalyzerDataSource {
  companion object {
    const val ANALYSIS_SAMPLE_RATE = 44100
    const val MAX_DURATION_SECONDS = 15 * 60
  }

  override fun probeSelectedAudio(context: Context, uri: Uri, fallbackDisplayName: String?): ProbedAudioInfo {
    return decoder.probeAudioInfo(context, uri, fallbackDisplayName)
  }

  override fun decodeAndPrepare(context: Context, uri: Uri): DecodedAudio {
    val decoded = decoder.decodeToMono(
      context = context,
      uri = uri,
      maxDurationSeconds = MAX_DURATION_SECONDS,
    )

    val prepared = if (decoded.sampleRate == ANALYSIS_SAMPLE_RATE) {
      decoded.pcmMono
    } else {
      resampler.resample(decoded.pcmMono, decoded.sampleRate, ANALYSIS_SAMPLE_RATE)
    }

    return decoded.copy(
      pcmMono = prepared,
      sampleRate = ANALYSIS_SAMPLE_RATE,
      channelCount = 1,
      durationSeconds = prepared.size.toDouble() / ANALYSIS_SAMPLE_RATE,
    )
  }

  override fun analyzePreparedAudio(audio: DecodedAudio): AnalysisResult {
    val json = EssentiaNativeBridge.analyzePcmFloat(
      pcmMono = audio.pcmMono,
      sampleRate = audio.sampleRate,
      fileName = audio.displayName,
    )
    return AnalysisJsonParser.parse(json)
  }
}
