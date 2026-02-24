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

data class AnalyzerUiState(
  val selectedUri: Uri? = null,
  val selectedFileName: String? = null,
  val status: AnalysisStatus = AnalysisStatus.Idle,
  val statusMessage: String = "파일을 선택하세요",
  val nativeInfo: String = "",
  val decodedAudio: DecodedAudio? = null,
  val result: AnalysisResult? = null,
  val errorMessage: String? = null,
)

class AnalyzerViewModel(
  private val repository: AnalyzerRepository = AnalyzerRepository(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    AnalyzerUiState(
      nativeInfo = runCatching { EssentiaNativeBridge.getNativeInfo() }
        .getOrElse { "Native info unavailable: ${it.message}" },
    )
  )
  val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()

  fun onFileSelecting() {
    _uiState.update {
      it.copy(status = AnalysisStatus.Picking, statusMessage = "파일 선택 중...")
    }
  }

  fun onFileSelected(uri: Uri?, displayName: String?) {
    if (uri == null) {
      _uiState.update {
        it.copy(
          status = AnalysisStatus.Idle,
          statusMessage = "파일 선택이 취소되었습니다",
        )
      }
      return
    }

    _uiState.update {
      it.copy(
        selectedUri = uri,
        selectedFileName = displayName ?: "selected_audio",
        decodedAudio = null,
        result = null,
        errorMessage = null,
        status = AnalysisStatus.Idle,
        statusMessage = "파일 선택 완료: ${displayName ?: "selected_audio"}",
      )
    }
  }

  fun analyze(context: Context) {
    val selected = _uiState.value.selectedUri ?: return

    viewModelScope.launch {
      runCatching {
        _uiState.update {
          it.copy(
            status = AnalysisStatus.Decoding,
            statusMessage = "오디오 디코딩 및 44.1kHz 변환 중...",
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
            errorMessage = throwable.message ?: "Unknown error",
          )
        }
      }
    }
  }
}
