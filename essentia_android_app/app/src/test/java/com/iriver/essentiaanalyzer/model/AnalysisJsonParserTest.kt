package com.iriver.essentiaanalyzer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisJsonParserTest {

  @Test
  fun `parse maps sections series and errors`() {
    val json = """
      {
        "meta": {"fileName":"song.wav","analysisSampleRate":44100},
        "summary": {"BPM":120.0},
        "temporal": {"duration":12.5},
        "spectral": {"rolloffMean":3400.25},
        "rhythm": {"confidence":0.81},
        "tonal": {"key":"C","scale":"major"},
        "highlevel": {"danceability":1.0},
        "stats": {"rms":0.24},
        "series": {
          "waveformEnvelope":[0.1,0.2],
          "spectralCentroid":[1000.0,1200.0],
          "onsetStrength":[0.01,0.02],
          "bpmHistogram":[{"bpm":120,"count":4}]
        },
        "errors":[
          {"algorithm":"Flux","message":"frame skipped"},
          {"algorithm":"native","message":"fatal native failure"}
        ]
      }
    """.trimIndent()

    val result = AnalysisJsonParser.parse(json)

    assertEquals("song.wav", result.meta["fileName"])
    assertEquals("120", result.summary["BPM"])
    assertEquals(2, result.series.waveformEnvelope.size)
    assertEquals(1, result.series.bpmHistogram.size)
    assertEquals(2, result.errors.size)
    assertEquals("Flux", result.errors.first().algorithm)
  }

  @Test
  fun `parse skips non finite values in numeric arrays`() {
    val json = """
      {
        "meta": {},
        "summary": {},
        "temporal": {},
        "spectral": {},
        "rhythm": {},
        "tonal": {},
        "highlevel": {},
        "stats": {},
        "series": {
          "waveformEnvelope":[1.0, null, "x", 2.0]
        },
        "errors":[]
      }
    """.trimIndent()

    val result = AnalysisJsonParser.parse(json)

    assertEquals(listOf(1.0f, 2.0f), result.series.waveformEnvelope)
    assertTrue(result.errors.isEmpty())
  }
}

