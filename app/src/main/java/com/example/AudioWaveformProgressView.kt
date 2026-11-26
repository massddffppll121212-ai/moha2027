package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class AudioWaveformProgressView
@JvmOverloads
constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

  interface OnWaveformSeekListener {
    fun onStartTrackingTouch(view: AudioWaveformProgressView)

    fun onProgressChanged(view: AudioWaveformProgressView, progressMs: Long, fromUser: Boolean)

    fun onStopTrackingTouch(view: AudioWaveformProgressView, progressMs: Long)
  }

  private val playedPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val unplayedPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#334155")
    }
  private val activeBarPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#7DD3FC")
    }
  private val playheadLinePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#F8FAFC")
    }
  private val playheadGlowPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#4D38BDF8")
    }

  private val barRect = RectF()
  private var amplitudes: List<Float> = AudioWaveformExtractor.fallbackWaveform("default")
  private var durationMs: Long = 1L
  private var positionMs: Long = 0L
  private var isPlayingAudio: Boolean = false
  private var isDragging: Boolean = false
  private var seekListener: OnWaveformSeekListener? = null

  private val density = resources.displayMetrics.density
  private val minBarHeightPx = 5f * density
  private val barGapPx = 3f * density
  private val cornerRadiusPx = 3f * density
  private val playheadRadiusPx = 5.5f * density
  private val playheadGlowRadiusPx = 10f * density

  init {
    isClickable = true
    isFocusable = true
  }

  fun setOnWaveformSeekListener(listener: OnWaveformSeekListener?) {
    seekListener = listener
  }

  fun setWaveformData(newAmplitudes: List<Float>) {
    val resolved =
      if (newAmplitudes.isNotEmpty()) newAmplitudes else AudioWaveformExtractor.fallbackWaveform("callvault")
    if (resolved != amplitudes) {
      amplitudes = resolved
      invalidate()
    }
  }

  fun setPlaybackState(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
  ) {
    val safeDuration = max(1L, durationMs)
    val clampedPos = positionMs.coerceIn(0L, safeDuration)
    val changed =
      this.durationMs != safeDuration ||
        this.positionMs != clampedPos ||
        this.isPlayingAudio != isPlaying
    this.durationMs = safeDuration
    this.positionMs = clampedPos
    this.isPlayingAudio = isPlaying
    if (changed || isPlaying) {
      invalidate()
    }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) {
      playedPaint.shader =
        LinearGradient(
          0f,
          0f,
          w.toFloat(),
          0f,
          Color.parseColor("#2563EB"),
          Color.parseColor("#38BDF8"),
          Shader.TileMode.CLAMP,
        )
    }
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val availWidth = (width - paddingLeft - paddingRight).toFloat()
    val availHeight = (height - paddingTop - paddingBottom).toFloat()
    if (availWidth <= 0f || availHeight <= 0f || amplitudes.isEmpty()) return

    val count = amplitudes.size
    val totalGap = barGapPx * (count - 1)
    val barWidth = max(2f * density, (availWidth - totalGap) / count)
    val actualStep = (availWidth - barWidth) / max(1, count - 1)
    val centerY = paddingTop + availHeight / 2f
    val maxBarHeight = max(minBarHeightPx, availHeight - 10f * density)

    val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val playheadX = paddingLeft + availWidth * fraction
    val pulsePhase =
      if (isPlayingAudio) {
        0.5f + 0.5f * kotlin.math.sin((System.currentTimeMillis() % 700L) / 700.0 * Math.PI * 2.0).toFloat()
      } else {
        0f
      }

    for (i in 0 until count) {
      val left = paddingLeft + i * actualStep
      val right = left + barWidth
      val barCenterX = (left + right) / 2f

      val baseAmp = amplitudes[i].coerceIn(0.16f, 1.0f)
      val distFromHead = kotlin.math.abs(barCenterX - playheadX) / max(1f, actualStep * 2.2f)
      val activeBoost =
        if (isPlayingAudio && distFromHead < 1f) {
          (1f - distFromHead) * 0.14f * pulsePhase
        } else {
          0f
        }

      val barHeight = max(minBarHeightPx, min(maxBarHeight, maxBarHeight * (baseAmp + activeBoost)))
      val top = centerY - barHeight / 2f
      val bottom = centerY + barHeight / 2f
      barRect.set(left, top, right, bottom)

      val paint =
        when {
          barCenterX <= playheadX - actualStep * 0.45f -> playedPaint
          kotlin.math.abs(barCenterX - playheadX) <= actualStep * 0.55f -> activeBarPaint
          else -> unplayedPaint
        }

      canvas.drawRoundRect(barRect, cornerRadiusPx, cornerRadiusPx, paint)
    }

    // مؤشر موضع التشغيل الحالي
    if (isDragging || isPlayingAudio || positionMs > 0L) {
      val indicatorX = playheadX.coerceIn(paddingLeft + playheadRadiusPx, width - paddingRight - playheadRadiusPx)
      canvas.drawCircle(indicatorX, centerY, playheadGlowRadiusPx, playheadGlowPaint)
      canvas.drawCircle(indicatorX, centerY, playheadRadiusPx, playheadLinePaint)
    }

    if (isPlayingAudio) {
      postInvalidateOnAnimation()
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (!isEnabled) return false

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        parent?.requestDisallowInterceptTouchEvent(true)
        isDragging = true
        seekListener?.onStartTrackingTouch(this)
        updateSeekFromTouch(event.x)
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (isDragging) {
          updateSeekFromTouch(event.x)
          return true
        }
      }
      MotionEvent.ACTION_UP -> {
        if (isDragging) {
          updateSeekFromTouch(event.x)
          isDragging = false
          seekListener?.onStopTrackingTouch(this, positionMs)
          performClick()
          parent?.requestDisallowInterceptTouchEvent(false)
          return true
        }
      }
      MotionEvent.ACTION_CANCEL -> {
        if (isDragging) {
          isDragging = false
          seekListener?.onStopTrackingTouch(this, positionMs)
          parent?.requestDisallowInterceptTouchEvent(false)
          return true
        }
      }
    }
    return super.onTouchEvent(event)
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  private fun updateSeekFromTouch(touchX: Float) {
    val availWidth = max(1f, (width - paddingLeft - paddingRight).toFloat())
    val ratio = ((touchX - paddingLeft) / availWidth).coerceIn(0f, 1f)
    val newPositionMs = (durationMs * ratio).toLong().coerceIn(0L, durationMs)
    positionMs = newPositionMs
    invalidate()
    seekListener?.onProgressChanged(this, newPositionMs, true)
  }
}
