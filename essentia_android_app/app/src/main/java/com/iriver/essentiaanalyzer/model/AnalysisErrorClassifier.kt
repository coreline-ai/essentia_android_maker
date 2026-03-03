package com.iriver.essentiaanalyzer.model

import java.util.Locale

fun isFatalAnalysisError(error: AnalysisError): Boolean {
  val algorithm = error.algorithm.lowercase(Locale.US)
  val message = error.message.lowercase(Locale.US)
  return algorithm == "native" ||
    message.contains("fatal") ||
    message.contains("null pcm input") ||
    message.contains("invalid sample rate")
}

