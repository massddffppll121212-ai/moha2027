package com.example

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object AudioWaveformExtractor {

  private const val TAG = "AudioWaveformExtractor"
  const val DEFAULT_BAR_COUNT = 48

  fun extractWaveform(
    context: Context,
    uri: Uri,
    legacyFilePath: String?,
    seedKey: String,
    barCount: Int = DEFAULT_BAR_COUNT,
  ): List<Float> {
    val fromExtractor = extractUsingMediaExtractor(context, uri, legacyFilePath, barCount)
    if (fromExtractor != null && hasMeaningfulVariation(fromExtractor)) {
      return fromExtractor
    }

    val fromStream = extractUsingStreamSampling(context, uri, legacyFilePath, barCount)
    if (fromStream != null && hasMeaningfulVariation(fromStream)) {
      return fromStream
    }

    return fallbackWaveform(seedKey, barCount)
  }

  fun fallbackWaveform(seedKey: String, barCount: Int = DEFAULT_BAR_COUNT): List<Float> {
    val rng = Random(seedKey.hashCode())
    return List(barCount) { idx ->
      val wave = 0.42f + 0.25f * kotlin.math.sin(idx * 0.45f)
      val jitter = rng.nextFloat() * 0.35f
      (wave + jitter).coerceIn(0.18f, 0.96f)
    }
  }

  private fun extractUsingMediaExtractor(
    context: Context,
    uri: Uri,
    legacyFilePath: String?,
    barCount: Int,
  ): List<Float>? {
    val extractor = MediaExtractor()
    return try {
      val legacyFile = legacyFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
      if (legacyFile != null) {
        extractor.setDataSource(legacyFile.absolutePath)
      } else {
        extractor.setDataSource(context, uri, null)
      }

      var audioTrackIndex = -1
      var durationUs = 0L
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("audio/")) {
          audioTrackIndex = i
          durationUs =
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
              format.getLong(MediaFormat.KEY_DURATION)
            } else {
              0L
            }
          break
        }
      }
      if (audioTrackIndex < 0) return null

      extractor.selectTrack(audioTrackIndex)

      val bucketTotals = DoubleArray(barCount)
      val bucketCounts = IntArray(barCount)
      val rawSizes = ArrayList<Long>(512)

      var sampleCount = 0
      val maxSamplesToScan = 6000
      while (sampleCount < maxSamplesToScan) {
        val sampleSize = extractor.sampleSize
        if (sampleSize <= 0L) break
        val sampleTimeUs = extractor.sampleTime

        if (durationUs > 0L && sampleTimeUs >= 0L) {
          val bucket =
            ((sampleTimeUs.toDouble() / durationUs.toDouble()) * barCount)
              .toInt()
              .coerceIn(0, barCount - 1)
          bucketTotals[bucket] += sampleSize.toDouble()
          bucketCounts[bucket]++
        } else {
          rawSizes.add(sampleSize)
        }

        sampleCount++
        if (!extractor.advance()) break
      }

      val rawBars = FloatArray(barCount)
      if (durationUs > 0L && bucketCounts.any { it > 0 }) {
        for (i in 0 until barCount) {
          rawBars[i] =
            if (bucketCounts[i] > 0) (bucketTotals[i] / bucketCounts[i]).toFloat() else 0f
        }
      } else if (rawSizes.isNotEmpty()) {
        val chunkSize = max(1, rawSizes.size / barCount)
        for (i in 0 until barCount) {
          val start = min(rawSizes.size - 1, i * chunkSize)
          val end = min(rawSizes.size, start + chunkSize)
          var sum = 0.0
          for (j in start until end) sum += rawSizes[j]
          rawBars[i] = (sum / max(1, end - start)).toFloat()
        }
      } else {
        return null
      }

      normalizeBars(rawBars)
    } catch (e: Exception) {
      Log.d(TAG, "MediaExtractor waveform skipped: ${e.message}")
      null
    } finally {
      try {
        extractor.release()
      } catch (_: Exception) {
      }
    }
  }

  private fun extractUsingStreamSampling(
    context: Context,
    uri: Uri,
    legacyFilePath: String?,
    barCount: Int,
  ): List<Float>? {
    return try {
      val stream: InputStream? =
        if (legacyFilePath != null && File(legacyFilePath).exists()) {
          FileInputStream(File(legacyFilePath))
        } else {
          context.contentResolver.openInputStream(uri)
        }

      stream?.use { input ->
        val allBytes = input.readBytes()
        if (allBytes.size < barCount * 8) return null

        val headerSkip = min(allBytes.size / 12, 2048)
        val payloadLength = allBytes.size - headerSkip
        if (payloadLength < barCount) return null

        val step = payloadLength.toDouble() / barCount.toDouble()
        val rawBars = FloatArray(barCount)
        for (i in 0 until barCount) {
          val start = headerSkip + (i * step).toInt()
          val end = min(allBytes.size, headerSkip + ((i + 1) * step).toInt())
          val windowEnd = min(end, start + 512)
          if (windowEnd - start <= 1) {
            rawBars[i] = 0.3f
            continue
          }
          var diffSum = 0L
          var prev = allBytes[start].toInt() and 0xFF
          for (idx in (start + 1) until windowEnd) {
            val cur = allBytes[idx].toInt() and 0xFF
            diffSum += abs(cur - prev)
            prev = cur
          }
          rawBars[i] = diffSum.toFloat() / max(1, windowEnd - start - 1).toFloat()
        }
        normalizeBars(rawBars)
      }
    } catch (e: Exception) {
      Log.d(TAG, "Stream waveform sampling skipped: ${e.message}")
      null
    }
  }

  private fun normalizeBars(rawBars: FloatArray): List<Float> {
    var minVal = Float.MAX_VALUE
    var maxVal = Float.MIN_VALUE
    for (value in rawBars) {
      if (value > 0f) {
        if (value < minVal) minVal = value
        if (value > maxVal) maxVal = value
      }
    }
    if (minVal == Float.MAX_VALUE || maxVal <= 0f) {
      return List(rawBars.size) { 0.4f }
    }
    val range = max(0.0001f, maxVal - minVal)
    return rawBars.map { raw ->
      if (raw <= 0f) {
        0.18f
      } else {
        val normalized = (raw - minVal) / range
        (0.18f + normalized * 0.82f).coerceIn(0.18f, 1.0f)
      }
    }
  }

  private fun hasMeaningfulVariation(bars: List<Float>): Boolean {
    if (bars.isEmpty()) return false
    val min = bars.minOrNull() ?: 0f
    val max = bars.maxOrNull() ?: 0f
    return (max - min) >= 0.12f
  }
}
