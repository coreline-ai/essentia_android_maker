package com.iriver.essentiaanalyzer.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisExplanationCardsTest {

  @Test
  fun rendersKoreanExplanationCards() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    launchPreviewActivity()
    assumeTrue(
      "Foreground window unavailable on this device",
      device.wait(Until.hasObject(By.pkg("com.iriver.essentiaanalyzer")), 4_000)
    )
    assumeTrue("Summary label not observable", device.wait(Until.hasObject(By.textContains("Summary")), 7_000))
    assertNotNull(device.findObject(By.textContains("Summary")))
    assertNotNull(device.findObject(By.textContains("Metrics")))
    assertNotNull(device.findObject(By.textContains("Cautions")))
  }

  @Test
  fun showsWarningAndFatalMessagesInCautionCard() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    launchPreviewActivity()
    assumeTrue(
      "Foreground window unavailable on this device",
      device.wait(Until.hasObject(By.pkg("com.iriver.essentiaanalyzer")), 4_000)
    )
    assumeTrue("FATAL marker not observable", device.wait(Until.hasObject(By.textContains("FATAL:")), 7_000))
    assertNotNull(device.findObject(By.textContains("FATAL:")))
    assertNotNull(device.findObject(By.textContains("WARN:")))
  }

  private fun launchPreviewActivity() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.executeShellCommand(
      "am start -W -n com.iriver.essentiaanalyzer/com.iriver.essentiaanalyzer.ui.AnalysisExplanationTestActivity"
    ).close()
  }
}
