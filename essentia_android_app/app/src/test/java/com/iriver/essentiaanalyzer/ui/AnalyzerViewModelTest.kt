package com.iriver.essentiaanalyzer.ui

import android.content.Context
import android.net.Uri
import com.iriver.essentiaanalyzer.data.AnalyzerDataSource
import com.iriver.essentiaanalyzer.data.DecodedAudio
import com.iriver.essentiaanalyzer.data.ProbedAudioInfo
import com.iriver.essentiaanalyzer.model.AnalysisError
import com.iriver.essentiaanalyzer.model.AnalysisResult
import com.iriver.essentiaanalyzer.model.AnalysisSeries
import com.iriver.essentiaanalyzer.model.AnalysisStatus
import com.iriver.essentiaanalyzer.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzerViewModelTest {

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val context: Context = mock(Context::class.java)

  @Test
  fun initialStateBecomesErrorWhenNativeProviderFails() = runTest {
    val viewModel = AnalyzerViewModel(
      repository = SuccessAnalyzerDataSource(),
      nativeInfoProvider = { error("native unavailable") },
      workerDispatcher = mainDispatcherRule.dispatcher,
    )
    advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(AnalysisStatus.Error, state.status)
    assertFalse(state.isNativeAvailable)
  }

  @Test
  fun analyzeWithoutFileSetsErrorState() = runTest {
    val viewModel = AnalyzerViewModel(
      repository = SuccessAnalyzerDataSource(),
      nativeInfoProvider = { "ok" },
      workerDispatcher = mainDispatcherRule.dispatcher,
    )
    advanceUntilIdle()

    viewModel.analyze(context)

    val state = viewModel.uiState.value
    assertEquals(AnalysisStatus.Error, state.status)
    assertNotNull(state.errorMessage)
  }

  @Test
  fun analyzeSuccessClearsHeavyPcmAndReportsWarnings() = runTest {
    val viewModel = AnalyzerViewModel(
      repository = SuccessAnalyzerDataSource(),
      nativeInfoProvider = { "ok" },
      workerDispatcher = mainDispatcherRule.dispatcher,
    )
    advanceUntilIdle()

    val uri = mock(Uri::class.java)
    viewModel.onFileSelected(context, uri, "song.mp3")
    assertEquals("분석 준비 완료", viewModel.uiState.value.statusMessage)
    viewModel.analyze(context)
    advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(AnalysisStatus.Done, state.status)
    assertTrue(state.statusMessage.isNotBlank())
    assertNotNull(state.result)
    assertNotNull(state.decodedAudio)
    assertTrue(state.decodedAudio!!.pcmMono.isEmpty())
  }

  @Test
  fun fileSelectionWhileNativeInitializingShowsInitializingMessage() = runTest {
    val viewModel = AnalyzerViewModel(
      repository = SuccessAnalyzerDataSource(),
      nativeInfoProvider = { "ok" },
      workerDispatcher = mainDispatcherRule.dispatcher,
    )

    val uri = mock(Uri::class.java)
    viewModel.onFileSelected(context, uri, "song.mp3")

    val beforeInitState = viewModel.uiState.value
    assertTrue(beforeInitState.isNativeInitializing)
    assertTrue(beforeInitState.statusMessage.contains("초기화 중"))

    advanceUntilIdle()
    assertEquals("분석 준비 완료", viewModel.uiState.value.statusMessage)
    assertFalse(viewModel.uiState.value.isNativeInitializing)
    assertTrue(viewModel.uiState.value.isNativeAvailable)
  }

  @Test
  fun analyzeDuringNativeInitializingDoesNotSetError() = runTest {
    val viewModel = AnalyzerViewModel(
      repository = SuccessAnalyzerDataSource(),
      nativeInfoProvider = { "ok" },
      workerDispatcher = mainDispatcherRule.dispatcher,
    )

    val uri = mock(Uri::class.java)
    viewModel.onFileSelected(context, uri, "song.mp3")
    viewModel.analyze(context)

    val beforeInitState = viewModel.uiState.value
    assertEquals(AnalysisStatus.Idle, beforeInitState.status)
    assertNull(beforeInitState.errorMessage)
    assertTrue(beforeInitState.statusMessage.contains("초기화 중"))

    advanceUntilIdle()
    assertEquals("분석 준비 완료", viewModel.uiState.value.statusMessage)
  }

  @Test
  fun fileSelectionCancelKeepsExistingSelection() = runTest {
    val viewModel = AnalyzerViewModel(
      repository = SuccessAnalyzerDataSource(),
      nativeInfoProvider = { "ok" },
      workerDispatcher = mainDispatcherRule.dispatcher,
    )
    advanceUntilIdle()

    val uri = mock(Uri::class.java)
    viewModel.onFileSelected(context, uri, "song.mp3")
    val beforeCancel = viewModel.uiState.value
    assertNotNull(beforeCancel.selectedUri)

    viewModel.onFileSelected(context, null, null)

    val state = viewModel.uiState.value
    assertNotNull(state.selectedUri)
    assertEquals(AnalysisStatus.Idle, state.status)
    assertTrue(state.statusMessage.contains("기존 파일을 유지합니다"))
  }

  @Test
  fun fileSelectionPreservesNativeUnavailableError() = runTest {
    val viewModel = AnalyzerViewModel(
      repository = SuccessAnalyzerDataSource(),
      nativeInfoProvider = { error("native unavailable") },
      workerDispatcher = mainDispatcherRule.dispatcher,
    )
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.isNativeAvailable)

    val uri = mock(Uri::class.java)
    viewModel.onFileSelected(context, uri, "song.mp3")

    val state = viewModel.uiState.value
    assertEquals(AnalysisStatus.Idle, state.status)
    assertNotNull(state.errorMessage)
    assertTrue(state.errorMessage!!.contains("native unavailable"))
    assertTrue(state.statusMessage.contains("네이티브 엔진을 사용할 수 없습니다"))
  }

  @Test
  fun interruptedAnalysisIsHandledAsCancellationNotFailure() = runTest {
    val viewModel = AnalyzerViewModel(
      repository = InterruptedDecodeAnalyzerDataSource(),
      nativeInfoProvider = { "ok" },
      workerDispatcher = mainDispatcherRule.dispatcher,
    )
    advanceUntilIdle()

    val uri = mock(Uri::class.java)
    viewModel.onFileSelected(context, uri, "song.mp3")
    viewModel.analyze(context)
    advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(AnalysisStatus.Idle, state.status)
    assertNull(state.errorMessage)
    assertNull(state.result)
  }
}

private class SuccessAnalyzerDataSource : AnalyzerDataSource {
  override fun probeSelectedAudio(context: Context, uri: Uri, fallbackDisplayName: String?): ProbedAudioInfo {
    return ProbedAudioInfo(
      fileName = fallbackDisplayName ?: "song.mp3",
      mimeType = "audio/mpeg",
      fileSizeBytes = 1024L,
      durationSeconds = 2.5,
      sampleRate = 44100,
      channelCount = 2,
    )
  }

  override fun decodeAndPrepare(context: Context, uri: Uri): DecodedAudio {
    return DecodedAudio(
      pcmMono = floatArrayOf(0.1f, 0.2f, 0.3f),
      sampleRate = 44100,
      channelCount = 1,
      durationSeconds = 3.0 / 44100.0,
      displayName = "song.mp3",
    )
  }

  override fun analyzePreparedAudio(audio: DecodedAudio): AnalysisResult {
    return AnalysisResult(
      rawJson = "{}",
      meta = mapOf("fileName" to audio.displayName),
      summary = mapOf("BPM" to "120"),
      temporal = emptyMap(),
      spectral = emptyMap(),
      rhythm = emptyMap(),
      tonal = emptyMap(),
      highlevel = emptyMap(),
      stats = emptyMap(),
      series = AnalysisSeries(),
      errors = listOf(AnalysisError(algorithm = "Flux", message = "frame skipped")),
    )
  }
}

private class InterruptedDecodeAnalyzerDataSource : AnalyzerDataSource {
  override fun probeSelectedAudio(context: Context, uri: Uri, fallbackDisplayName: String?): ProbedAudioInfo {
    return ProbedAudioInfo(
      fileName = fallbackDisplayName ?: "song.mp3",
      mimeType = "audio/mpeg",
      fileSizeBytes = 1024L,
      durationSeconds = 2.5,
      sampleRate = 44100,
      channelCount = 2,
    )
  }

  override fun decodeAndPrepare(context: Context, uri: Uri): DecodedAudio {
    throw InterruptedException("decode canceled")
  }

  override fun analyzePreparedAudio(audio: DecodedAudio): AnalysisResult {
    error("analyzePreparedAudio should not be called when decode is interrupted")
  }
}
