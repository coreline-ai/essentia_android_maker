package com.iriver.essentiaanalyzer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LinearResamplerTest {

  private val resampler = LinearResampler()

  @Test
  fun `same rate returns same reference`() {
    val input = floatArrayOf(0f, 0.5f, -0.25f, 1f)

    val output = resampler.resample(input, inputRate = 44100, outputRate = 44100)

    assertSame(input, output)
  }

  @Test
  fun `upsample linearly interpolates`() {
    val input = floatArrayOf(0f, 1f)

    val output = resampler.resample(input, inputRate = 2, outputRate = 4)

    assertEquals(4, output.size)
    assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 1f), output, 1e-6f)
  }

  @Test
  fun `downsample preserves endpoints approximately`() {
    val input = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f)

    val output = resampler.resample(input, inputRate = 6, outputRate = 3)

    assertEquals(3, output.size)
    assertArrayEquals(floatArrayOf(0f, 2f, 4f), output, 1e-6f)
  }
}

