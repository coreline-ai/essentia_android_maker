package com.iriver.essentiaanalyzer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisWarningGrouperTest {

  @Test
  fun `group warnings excludes native and sorts by algorithm`() {
    val errors = listOf(
      AnalysisError("Flux", "frame skipped"),
      AnalysisError("native", "fatal"),
      AnalysisError("MFCC", "fatal computation error"),
      AnalysisError("MFCC", "nan"),
      AnalysisError("Flux", "silent frame"),
      AnalysisError("", "missing algorithm"),
    )

    val grouped = groupWarningsByAlgorithm(errors)

    assertEquals(3, grouped.size)
    assertEquals("Flux", grouped[0].algorithm)
    assertEquals("MFCC", grouped[1].algorithm)
    assertEquals("unknown", grouped[2].algorithm)
    assertEquals(listOf("frame skipped", "silent frame"), grouped[0].messages)
  }

  @Test
  fun `group warnings empty for native-only errors`() {
    val grouped = groupWarningsByAlgorithm(
      listOf(AnalysisError("native", "fatal"))
    )

    assertTrue(grouped.isEmpty())
  }
}
