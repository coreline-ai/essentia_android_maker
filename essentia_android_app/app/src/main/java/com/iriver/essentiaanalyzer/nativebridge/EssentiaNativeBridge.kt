package com.iriver.essentiaanalyzer.nativebridge

object EssentiaNativeBridge {
  init {
    System.loadLibrary("audioanalyzer_jni")
  }

  external fun analyzePcmFloat(pcmMono: FloatArray, sampleRate: Int, fileName: String): String
  external fun getNativeInfo(): String
}
