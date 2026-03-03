package com.iriver.essentiaanalyzer

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

  @Test
  fun appContextHasExpectedPackage() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("com.iriver.essentiaanalyzer", targetContext.packageName)
  }

  @Test
  fun mainActivityIsResolvable() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val launchIntent = Intent(Intent.ACTION_MAIN).setClassName(
      targetContext,
      "com.iriver.essentiaanalyzer.MainActivity",
    )

    val resolved = targetContext.packageManager.resolveActivity(launchIntent, 0)
    assertNotNull("MainActivity should be resolvable in target package", resolved)
  }
}
