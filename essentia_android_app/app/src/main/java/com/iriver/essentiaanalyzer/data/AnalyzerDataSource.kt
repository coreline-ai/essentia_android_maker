package com.iriver.essentiaanalyzer.data

import android.content.Context
import android.net.Uri
import com.iriver.essentiaanalyzer.model.AnalysisResult

interface AnalyzerDataSource {
  fun probeSelectedAudio(context: Context, uri: Uri, fallbackDisplayName: String?): ProbedAudioInfo
  fun decodeAndPrepare(context: Context, uri: Uri): DecodedAudio
  fun analyzePreparedAudio(audio: DecodedAudio): AnalysisResult
}

