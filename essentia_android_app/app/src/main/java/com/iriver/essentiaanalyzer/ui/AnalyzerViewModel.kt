package com.iriver.essentiaanalyzer.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iriver.essentiaanalyzer.data.AnalyzerRepository
import com.iriver.essentiaanalyzer.data.DecodedAudio
import com.iriver.essentiaanalyzer.model.AnalysisResult
import com.iriver.essentiaanalyzer.model.AnalysisStatus
import com.iriver.essentiaanalyzer.nativebridge.EssentiaNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
  val decodedAudio: DecodedAudio? = null,
  val result: AnalysisResult? = null,
  val errorMessage: String? = null,
)

class AnalyzerViewModel(
  private val repository: AnalyzerRepository = AnalyzerRepository(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(createInitialState())
  val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()

  private fun createInitialState(): AnalyzerUiState {
    val native = runCatching { EssentiaNativeBridge.getNativeInfo() }
    return if (native.isSuccess) {
      AnalyzerUiState(
        nativeInfo = native.getOrNull().orEmpty(),
        isNativeAvailable = true,
      )
    } else {
      AnalyzerUiState(
        nativeInfo = "네이티브 초기화 실패: ${native.exceptionOrNull()?.message}",
        isNativeAvailable = false,
        status = AnalysisStatus.Error,
        statusMessage = "네이티브 엔진을 사용할 수 없습니다.",
        errorMessage = native.exceptionOrNull()?.message ?: "Unknown native initialization error",
      )
    }
  }

  fun onFileSelecting() {
    _uiState.update {
      it.copy(status = AnalysisStatus.Picking, statusMessage = "파일 선택 중...")
    }
  }

  fun onFileSelected(context: Context, uri: Uri?, displayName: String?) {
    if (uri == null) {
      _uiState.update {
        it.copy(
          status = AnalysisStatus.Idle,
          statusMessage = "파일 선택이 취소되었습니다.",
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
      it.copy(
        selectedUri = uri,
        selectedFileInfo = selectedInfo,
        decodedAudio = null,
        result = null,
        errorMessage = metadataError,
        status = AnalysisStatus.Idle,
        statusMessage = if (metadataError == null) {
          "파일 선택 완료: ${selectedInfo.fileName}"
        } else {
          "파일 선택 완료(메타 일부 누락)"
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
      _uiState.update {
        it.copy(
          status = AnalysisStatus.Error,
          statusMessage = "네이티브 엔진을 사용할 수 없습니다.",
          errorMessage = it.errorMessage ?: "네이티브 라이브러리 로딩 상태를 확인하세요.",
        )
      }
      return
    }

    viewModelScope.launch {
      runCatching {
        _uiState.update {
          it.copy(
            status = AnalysisStatus.Decoding,
            statusMessage = "디코딩 및 44.1kHz 변환 중...",
            errorMessage = null,
          )
        }

        val decoded = withContext(Dispatchers.Default) {
          repository.decodeAndPrepare(context, selected)
        }

        _uiState.update {
          it.copy(
            decodedAudio = decoded,
            status = AnalysisStatus.Analyzing,
            statusMessage = "Essentia 분석 중...",
          )
        }

        val result = withContext(Dispatchers.Default) {
          repository.analyzePreparedAudio(decoded)
        }

        _uiState.update {
          it.copy(
            result = result,
            status = AnalysisStatus.Done,
            statusMessage = "분석 완료",
          )
        }
      }.onFailure { throwable ->
        _uiState.update {
          it.copy(
            status = AnalysisStatus.Error,
            statusMessage = "분석 실패",
            errorMessage = "분석 실패: ${throwable.message ?: "Unknown error"}",
          )
        }
      }
    }
  }
}
