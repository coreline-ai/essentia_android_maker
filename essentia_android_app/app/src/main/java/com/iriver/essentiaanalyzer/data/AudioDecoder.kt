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

data class DecodedAudio(
  val pcmMono: FloatArray,
  val sampleRate: Int,
  val channelCount: Int,
  val durationSeconds: Double,
  val displayName: String,
)

class AudioDecoder {
  fun decodeToMono(
    context: Context,
    uri: Uri,
    maxDurationSeconds: Int = 900,
  ): DecodedAudio {
    val extractor = MediaExtractor()

    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
      extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
    } ?: extractor.setDataSource(context, uri, null)

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

    val decoder = MediaCodec.createDecoderByType(mime)
    decoder.configure(format, null, null, 0)
    decoder.start()

    val info = MediaCodec.BufferInfo()
    val output = FloatAccumulator(initialCapacity = max(4096, sourceSampleRate * 30))

    var inputDone = false
    var outputDone = false
    var outChannels = sourceChannels
    var outEncoding = AudioFormat.ENCODING_PCM_16BIT

    while (!outputDone) {
      if (!inputDone) {
        val inputBufferIndex = decoder.dequeueInputBuffer(10_000)
        if (inputBufferIndex >= 0) {
          val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
            ?: throw IllegalStateException("Decoder input buffer is null")

          val sampleSize = extractor.readSampleData(inputBuffer, 0)
          if (sampleSize < 0) {
            decoder.queueInputBuffer(
              inputBufferIndex,
              0,
              0,
              0,
              MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            inputDone = true
          } else {
            decoder.queueInputBuffer(
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

      val outputBufferIndex = decoder.dequeueOutputBuffer(info, 10_000)
      when {
        outputBufferIndex >= 0 -> {
          val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
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
          }

          decoder.releaseOutputBuffer(outputBufferIndex, false)
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            outputDone = true
          }
        }

        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          val outFormat = decoder.outputFormat
          outChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          outEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
          } else {
            AudioFormat.ENCODING_PCM_16BIT
          }
        }
      }
    }

    decoder.stop()
    decoder.release()
    extractor.release()

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
      displayName = queryDisplayName(context, uri),
    )
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
      var sum = 0.0f
      val base = f * channelCount
      for (c in 0 until channelCount) {
        sum += interleaved[base + c]
      }
      output.append(sum / channelCount)
    }
  }

  private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
          return cursor.getString(index) ?: "selected_audio"
        }
      }
    return "selected_audio"
  }
}

private class FloatAccumulator(initialCapacity: Int = 1024) {
  private var data = FloatArray(initialCapacity.coerceAtLeast(1))
  private var size = 0

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
