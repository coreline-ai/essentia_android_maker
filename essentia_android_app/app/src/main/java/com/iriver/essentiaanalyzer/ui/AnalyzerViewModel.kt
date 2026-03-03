package com.iriver.essentiaanalyzer.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iriver.essentiaanalyzer.data.AnalyzerDataSource
import com.iriver.essentiaanalyzer.data.AnalyzerRepository
import com.iriver.essentiaanalyzer.data.DecodedAudio
import com.iriver.essentiaanalyzer.model.AnalysisResult
import com.iriver.essentiaanalyzer.model.AnalysisStatus
import com.iriver.essentiaanalyzer.model.isFatalAnalysisError
import com.iriver.essentiaanalyzer.nativebridge.EssentiaNativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SelectedFileInfo(
  val fileName: String,
  val mimeType: String?,
  val fileSizeBytes: Long?,
  val durationSec: Double?,
  val sampleRate: Int?,
  val channelCount: Int?,
)

data class AnalyzerUiState(
  val selectedUri: Uri? = null,
  val selectedFileInfo: SelectedFileInfo? = null,
  val status: AnalysisStatus = AnalysisStatus.Idle,
  val statusMessage: String = "파일을 선택하세요.",
  val nativeInfo: String = "",
  val isNativeAvailable: Boolean = false,
  val isNativeInitializing: Boolean = true,
  val decodedAudio: DecodedAudio? = null,
  val result: AnalysisResult? = null,
  val errorMessage: String? = null,
)

class AnalyzerViewModel(
  private val repository: AnalyzerDataSource = AnalyzerRepository(),
  private val nativeInfoProvider: () -> String = { EssentiaNativeBridge.getNativeInfo() },
  private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

  companion object {
    private val EMPTY_PCM_PREVIEW = FloatArray(0)
  }

  private val _uiState = MutableStateFlow(createInitialState())
  val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()
  private var analysisJob: Job? = null
  private var analysisToken: Long = 0L

  init {
    initializeNativeAsync()
  }

  private fun invalidateInFlightAnalysis() {
    analysisToken += 1L
    analysisJob?.cancel()
    analysisJob = null
  }

  private fun idleStatusMessage(
    selectedUri: Uri?,
    isNativeAvailable: Boolean,
    isNativeInitializing: Boolean,
  ): String {
    if (isNativeInitializing) return "네이티브 엔진 초기화 중..."
    if (!isNativeAvailable) return "네이티브 엔진을 사용할 수 없습니다."
    return if (selectedUri != null) "분석 준비 완료" else "파일을 선택하세요."
  }

  private fun isCancellationFailure(throwable: Throwable): Boolean {
    return when (throwable) {
      is CancellationException,
      is InterruptedException -> true
      else -> {
        val cause = throwable.cause
        cause != null && cause !== throwable && isCancellationFailure(cause)
      }
    }
  }

  private fun createInitialState(): AnalyzerUiState {
    return AnalyzerUiState(
      nativeInfo = "네이티브 초기화 중...",
      isNativeAvailable = false,
      isNativeInitializing = true,
      status = AnalysisStatus.Idle,
      statusMessage = "네이티브 엔진 초기화 중...",
    )
  }

  private fun initializeNativeAsync() {
    viewModelScope.launch {
      runCatching {
        runInterruptible(workerDispatcher) { nativeInfoProvider() }
      }.onSuccess { info ->
        _uiState.update { state ->
          val shouldClearNativeError = state.status == AnalysisStatus.Error &&
            (state.errorMessage?.contains("네이티브") == true ||
              state.statusMessage.contains("네이티브"))

          state.copy(
            nativeInfo = info,
            isNativeAvailable = true,
            isNativeInitializing = false,
            status = if (shouldClearNativeError) AnalysisStatus.Idle else state.status,
            statusMessage = when {
              shouldClearNativeError -> idleStatusMessage(
                selectedUri = state.selectedUri,
                isNativeAvailable = true,
                isNativeInitializing = false,
              )
              state.statusMessage == "네이티브 엔진 초기화 중..." -> idleStatusMessage(
                selectedUri = state.selectedUri,
                isNativeAvailable = true,
                isNativeInitializing = false,
              )
              else -> state.statusMessage
            },
            errorMessage = if (shouldClearNativeError) null else state.errorMessage,
          )
        }
      }.onFailure { throwable ->
        _uiState.update { state ->
          state.copy(
            nativeInfo = "네이티브 초기화 실패: ${throwable.message}",
            isNativeAvailable = false,
            isNativeInitializing = false,
            status = AnalysisStatus.Error,
            statusMessage = "네이티브 엔진을 사용할 수 없습니다.",
            errorMessage = throwable.message ?: "Unknown native initialization error",
          )
        }
      }
    }
  }

  fun onFileSelecting() {
    _uiState.update {
      it.copy(status = AnalysisStatus.Picking, statusMessage = "파일 선택 중...")
    }
  }

  fun onFileSelected(context: Context, uri: Uri?, displayName: String?) {
    invalidateInFlightAnalysis()

    if (uri == null) {
      _uiState.update {
        val idleMessage = idleStatusMessage(
          selectedUri = it.selectedUri,
          isNativeAvailable = it.isNativeAvailable,
          isNativeInitializing = it.isNativeInitializing,
        )
        val canceledMessage = if (it.selectedUri != null) {
          "파일 선택이 취소되었습니다. 기존 파일을 유지합니다. $idleMessage"
        } else {
          "파일 선택이 취소되었습니다. $idleMessage"
        }
        it.copy(
          status = AnalysisStatus.Idle,
          statusMessage = canceledMessage,
        )
      }
      return
    }

    val fallbackName = displayName ?: "selected_audio"
    val probed = runCatching {
      repository.probeSelectedAudio(context, uri, displayName)
    }

    val selectedInfo = probed.getOrNull()?.let {
      SelectedFileInfo(
        fileName = it.fileName,
        mimeType = it.mimeType,
        fileSizeBytes = it.fileSizeBytes,
        durationSec = it.durationSeconds,
        sampleRate = it.sampleRate,
        channelCount = it.channelCount,
      )
    } ?: SelectedFileInfo(
      fileName = fallbackName,
      mimeType = context.contentResolver.getType(uri),
      fileSizeBytes = null,
      durationSec = null,
      sampleRate = null,
      channelCount = null,
    )

    val metadataError = probed.exceptionOrNull()?.let {
      "파일 메타 정보 조회 실패: ${it.message ?: "unknown"}"
    }

    _uiState.update {
      val preservedNativeError = if (!it.isNativeAvailable) it.errorMessage else null
      val nextError = listOfNotNull(preservedNativeError, metadataError)
        .takeIf { errors -> errors.isNotEmpty() }
        ?.joinToString(separator = "\n")

      it.copy(
        selectedUri = uri,
        selectedFileInfo = selectedInfo,
        decodedAudio = null,
        result = null,
        errorMessage = nextError,
        status = AnalysisStatus.Idle,
        statusMessage = if (metadataError == null) {
          idleStatusMessage(
            selectedUri = uri,
            isNativeAvailable = it.isNativeAvailable,
            isNativeInitializing = it.isNativeInitializing,
          )
        } else {
          "${idleStatusMessage(
            selectedUri = uri,
            isNativeAvailable = it.isNativeAvailable,
            isNativeInitializing = it.isNativeInitializing,
          )} (메타 일부 누락)"
        },
      )
    }
  }

  fun analyze(context: Context) {
    val current = _uiState.value
    val selected = current.selectedUri

    if (selected == null) {
      _uiState.update {
        it.copy(
          status = AnalysisStatus.Error,
          statusMessage = "분석할 파일이 선택되지 않았습니다.",
          errorMessage = "파일을 먼저 선택하세요.",
        )
      }
      return
    }

    if (current.status == AnalysisStatus.Decoding || current.status == AnalysisStatus.Analyzing) {
      return
    }

    if (!current.isNativeAvailable) {
      if (current.isNativeInitializing) {
        _uiState.update {
          it.copy(
            status = AnalysisStatus.Idle,
            statusMessage = idleStatusMessage(
              selectedUri = it.selectedUri,
              isNativeAvailable = false,
              isNativeInitializing = true,
            ),
            errorMessage = null,
          )
        }
        return
      }
      _uiState.update {
        it.copy(
          status = AnalysisStatus.Error,
          statusMessage = "네이티브 엔진을 사용할 수 없습니다.",
          errorMessage = it.errorMessage ?: "네이티브 라이브러리 로딩 상태를 확인하세요.",
        )
      }
      return
    }

    val token = analysisToken + 1L
    analysisToken = token

    analysisJob?.cancel()
    val job = viewModelScope.launch {
      runCatching {
        _uiState.update {
          it.copy(
            status = AnalysisStatus.Decoding,
            statusMessage = "디코딩 및 44.1kHz 변환 중...",
            result = null,
            errorMessage = null,
          )
        }

        val decoded = runInterruptible(workerDispatcher) {
          repository.decodeAndPrepare(context, selected)
        }
        if (analysisToken != token) return@launch

        val decodedPreview = decoded.copy(pcmMono = EMPTY_PCM_PREVIEW)
        _uiState.update {
          it.copy(
            decodedAudio = decodedPreview,
            status = AnalysisStatus.Analyzing,
            statusMessage = "Essentia 분석 중...",
          )
        }

        val result = runInterruptible(workerDispatcher) {
          repository.analyzePreparedAudio(decoded)
        }
        if (analysisToken != token) return@launch

        _uiState.update {
          val warningCount = result.errors.count { !isFatalAnalysisError(it) }
          it.copy(
            result = result,
            status = AnalysisStatus.Done,
            statusMessage = if (warningCount > 0) {
              "분석 완료 (경고 ${warningCount}건)"
            } else {
              "분석 완료"
            },
          )
        }
      }.onFailure { throwable ->
        if (analysisToken != token) return@onFailure
        if (isCancellationFailure(throwable)) {
          _uiState.update {
            it.copy(
              status = AnalysisStatus.Idle,
              statusMessage = idleStatusMessage(
                selectedUri = it.selectedUri,
                isNativeAvailable = it.isNativeAvailable,
                isNativeInitializing = it.isNativeInitializing,
              ),
              result = null,
              errorMessage = null,
            )
          }
          return@onFailure
        }
        _uiState.update {
          it.copy(
            status = AnalysisStatus.Error,
            statusMessage = "분석 실패",
            result = null,
            errorMessage = "분석 실패: ${throwable.message ?: "Unknown error"}",
          )
        }
      }
    }
    analysisJob = job
    job.invokeOnCompletion {
      if (analysisJob === job) {
        analysisJob = null
      }
    }
  }
}
