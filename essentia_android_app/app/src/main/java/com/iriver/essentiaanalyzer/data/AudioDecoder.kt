package com.iriver.essentiaanalyzer.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

data class ProbedAudioInfo(
  val fileName: String,
  val mimeType: String?,
  val fileSizeBytes: Long?,
  val durationSeconds: Double?,
  val sampleRate: Int?,
  val channelCount: Int?,
)

data class DecodedAudio(
  val pcmMono: FloatArray,
  val sampleRate: Int,
  val channelCount: Int,
  val durationSeconds: Double,
  val displayName: String,
)

class AudioDecoder {
  fun probeAudioInfo(
    context: Context,
    uri: Uri,
    fallbackDisplayName: String? = null,
  ): ProbedAudioInfo {
    val basicMeta = queryBasicFileMetadata(context, uri)
    val extractor = MediaExtractor()

    return try {
      setExtractorDataSource(extractor, context, uri)
      val trackIndex = findAudioTrack(extractor)
      require(trackIndex >= 0) { "Audio track not found" }

      extractor.selectTrack(trackIndex)
      val format = extractor.getTrackFormat(trackIndex)

      val mimeType = format.getString(MediaFormat.KEY_MIME) ?: basicMeta.mimeType
      val durationSeconds = if (format.containsKey(MediaFormat.KEY_DURATION)) {
        (format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0L } ?: -1L)
          .takeIf { it > 0L }
          ?.div(1_000_000.0)
      } else {
        null
      }

      val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      } else {
        null
      }

      val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      } else {
        null
      }

      ProbedAudioInfo(
        fileName = fallbackDisplayName ?: basicMeta.displayName ?: "selected_audio",
        mimeType = mimeType,
        fileSizeBytes = basicMeta.fileSizeBytes,
        durationSeconds = durationSeconds,
        sampleRate = sampleRate,
        channelCount = channelCount,
      )
    } finally {
      extractor.release()
    }
  }

  fun decodeToMono(
    context: Context,
    uri: Uri,
    maxDurationSeconds: Int = 900,
  ): DecodedAudio {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    var decoderStarted = false
    val basicMeta = queryBasicFileMetadata(context, uri)
    try {
      setExtractorDataSource(extractor, context, uri)

      val trackIndex = findAudioTrack(extractor)
      require(trackIndex >= 0) { "Audio track not found" }

      extractor.selectTrack(trackIndex)
      val format = extractor.getTrackFormat(trackIndex)
      val mime = format.getString(MediaFormat.KEY_MIME)
        ?: throw IllegalArgumentException("Missing audio mime type")

      val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
        format.getLong(MediaFormat.KEY_DURATION)
      } else {
        -1L
      }

      if (durationUs > 0) {
        val durationSeconds = durationUs / 1_000_000.0
        require(durationSeconds <= maxDurationSeconds) {
          "Audio too long (${durationSeconds.toInt()}s). Max allowed: ${maxDurationSeconds}s"
        }
      }

      val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      val maxOutputSamples = maxDurationSeconds.toLong() * sourceSampleRate.toLong()

      val codec = MediaCodec.createDecoderByType(mime)
      decoder = codec
      codec.configure(format, null, null, 0)
      codec.start()
      decoderStarted = true

      val info = MediaCodec.BufferInfo()
      val output = FloatAccumulator(initialCapacity = max(4096, sourceSampleRate * 30))

      var inputDone = false
      var outputDone = false
      var outChannels = sourceChannels
      var outEncoding = AudioFormat.ENCODING_PCM_16BIT

      while (!outputDone) {
        throwIfInterrupted()

        if (!inputDone) {
          val inputBufferIndex = codec.dequeueInputBuffer(10_000)
          if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
              ?: throw IllegalStateException("Decoder input buffer is null")

            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
              codec.queueInputBuffer(
                inputBufferIndex,
                0,
                0,
                0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
              )
              inputDone = true
            } else {
              codec.queueInputBuffer(
                inputBufferIndex,
                0,
                sampleSize,
                extractor.sampleTime,
                0,
              )
              extractor.advance()
            }
          }
        }

        val outputBufferIndex = codec.dequeueOutputBuffer(info, 10_000)
        when {
          outputBufferIndex >= 0 -> {
            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
            if (outputBuffer != null && info.size > 0) {
              outputBuffer.position(info.offset)
              outputBuffer.limit(info.offset + info.size)
              appendDecodedChunk(
                outputBuffer = outputBuffer,
                bytes = info.size,
                pcmEncoding = outEncoding,
                channelCount = outChannels,
                output = output,
              )
              if (output.length.toLong() > maxOutputSamples) {
                throw IllegalArgumentException(
                  "Audio too long (${maxDurationSeconds}s+). Max allowed: ${maxDurationSeconds}s"
                )
              }
            }

            codec.releaseOutputBuffer(outputBufferIndex, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              outputDone = true
            }
          }

          outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val outFormat = codec.outputFormat
            outChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            outEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
              outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
              AudioFormat.ENCODING_PCM_16BIT
            }
          }
        }
      }

      val pcm = output.toArray()
      val finalDuration = pcm.size.toDouble() / sourceSampleRate

      require(finalDuration <= maxDurationSeconds) {
        "Audio too long (${finalDuration.toInt()}s). Max allowed: ${maxDurationSeconds}s"
      }

      return DecodedAudio(
        pcmMono = pcm,
        sampleRate = sourceSampleRate,
        channelCount = sourceChannels,
        durationSeconds = finalDuration,
        displayName = basicMeta.displayName ?: "selected_audio",
      )
    } finally {
      if (decoderStarted) {
        runCatching { decoder?.stop() }
      }
      runCatching { decoder?.release() }
      runCatching { extractor.release() }
    }
  }

  private fun queryBasicFileMetadata(context: Context, uri: Uri): BasicFileMetadata {
    val mimeType = context.contentResolver.getType(uri)

    var displayName: String? = null
    var fileSizeBytes: Long? = null

    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
            displayName = cursor.getString(nameIndex)
          }

          val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
            fileSizeBytes = cursor.getLong(sizeIndex)
          }
        }
      }

    return BasicFileMetadata(
      displayName = displayName,
      mimeType = mimeType,
      fileSizeBytes = fileSizeBytes,
    )
  }

  private fun setExtractorDataSource(extractor: MediaExtractor, context: Context, uri: Uri) {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
      val configured = runCatching {
        if (afd.length >= 0L) {
          extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        } else {
          extractor.setDataSource(afd.fileDescriptor)
        }
      }.isSuccess
      if (configured) return
    }
    extractor.setDataSource(context, uri, null)
  }

  private fun findAudioTrack(extractor: MediaExtractor): Int {
    for (i in 0 until extractor.trackCount) {
      val format = extractor.getTrackFormat(i)
      val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith("audio/")) return i
    }
    return -1
  }

  private fun appendDecodedChunk(
    outputBuffer: ByteBuffer,
    bytes: Int,
    pcmEncoding: Int,
    channelCount: Int,
    output: FloatAccumulator,
  ) {
    when (pcmEncoding) {
      AudioFormat.ENCODING_PCM_FLOAT -> {
        val floatCount = bytes / 4
        val tmp = FloatArray(floatCount)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(tmp)
        appendDownmixed(tmp, channelCount, output)
      }

      AudioFormat.ENCODING_PCM_16BIT -> {
        val sampleCount = bytes / 2
        val tmp = ShortArray(sampleCount)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(tmp)
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
          floats[i] = (tmp[i] / 32768.0f).coerceIn(-1.0f, 1.0f)
        }
        appendDownmixed(floats, channelCount, output)
      }

      AudioFormat.ENCODING_PCM_8BIT -> {
        val tmp = ByteArray(bytes)
        outputBuffer.get(tmp)
        val floats = FloatArray(bytes)
        for (i in tmp.indices) {
          val unsigned = tmp[i].toInt() and 0xFF
          floats[i] = ((unsigned - 128) / 128.0f).coerceIn(-1.0f, 1.0f)
        }
        appendDownmixed(floats, channelCount, output)
      }

      else -> {
        val sampleCount = bytes / 2
        val tmp = ShortArray(sampleCount)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(tmp)
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
          floats[i] = (tmp[i] / 32768.0f).coerceIn(-1.0f, 1.0f)
        }
        appendDownmixed(floats, channelCount, output)
      }
    }
  }

  private fun appendDownmixed(
    interleaved: FloatArray,
    channelCount: Int,
    output: FloatAccumulator,
  ) {
    if (channelCount <= 1) {
      output.appendAll(interleaved)
      return
    }

    val frames = interleaved.size / channelCount
    for (f in 0 until frames) {
      if ((f and 0x3FF) == 0) {
        throwIfInterrupted()
      }
      var sum = 0.0f
      val base = f * channelCount
      for (c in 0 until channelCount) {
        sum += interleaved[base + c]
      }
      output.append(sum / channelCount)
    }
  }
}

private fun throwIfInterrupted() {
  if (Thread.currentThread().isInterrupted) {
    throw InterruptedException("Audio decode canceled")
  }
}

private data class BasicFileMetadata(
  val displayName: String?,
  val mimeType: String?,
  val fileSizeBytes: Long?,
)

private class FloatAccumulator(initialCapacity: Int = 1024) {
  private var data = FloatArray(initialCapacity.coerceAtLeast(1))
  private var size = 0
  val length: Int
    get() = size

  fun append(value: Float) {
    ensureCapacity(size + 1)
    data[size] = value
    size += 1
  }

  fun appendAll(values: FloatArray) {
    ensureCapacity(size + values.size)
    values.copyInto(data, destinationOffset = size)
    size += values.size
  }

  fun toArray(): FloatArray = data.copyOf(size)

  private fun ensureCapacity(required: Int) {
    if (required <= data.size) return
    var newSize = data.size
    while (newSize < required) {
      newSize *= 2
    }
    data = data.copyOf(newSize)
  }
}
