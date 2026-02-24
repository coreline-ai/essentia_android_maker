package com.iriver.essentiaanalyzer.data

class LinearResampler {
  fun resample(signal: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
    if (signal.isEmpty()) return signal
    if (inputRate <= 0 || outputRate <= 0) return signal
    if (inputRate == outputRate) return signal

    val ratio = outputRate.toDouble() / inputRate.toDouble()
    val outputLength = (signal.size * ratio).toInt().coerceAtLeast(1)
    val output = FloatArray(outputLength)

    for (i in 0 until outputLength) {
      val srcIndex = i / ratio
      val left = srcIndex.toInt().coerceIn(0, signal.lastIndex)
      val right = (left + 1).coerceAtMost(signal.lastIndex)
      val frac = (srcIndex - left).toFloat()
      output[i] = signal[left] * (1.0f - frac) + signal[right] * frac
    }

    return output
  }
}
