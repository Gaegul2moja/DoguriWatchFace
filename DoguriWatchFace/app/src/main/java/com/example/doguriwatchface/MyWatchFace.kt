package com.example.doguriwatchface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.DisplayMetrics
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.palette.graphics.Palette
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

private const val SECOND_TICK_STROKE_WIDTH = 2f

private const val SHADOW_RADIUS = 6f

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn"t
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 *
 *
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
class MyWatchFace : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: MyWatchFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<MyWatchFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {

        private lateinit var mCalendar: Calendar

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode: Boolean = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F

        /* Colors for all hands (hour, minute, seconds, ticks) based on photo loaded. */
        private var mWatchHandColor: Int = 0
        private var mWatchHandHighlightColor: Int = 0
        private var mWatchHandShadowColor: Int = 0

        private lateinit var mTickAndCirclePaint: Paint

        private lateinit var mBackgroundPaint: Paint
        private lateinit var mCriteriaBitmap: Bitmap
        private lateinit var mAmbientBackgroundBitmap: Bitmap
        private lateinit var mAmbientBackgroundBitmapAbnormal: Bitmap
        private lateinit var mGrayBackgroundBitmap: Bitmap
        private lateinit var mGrayAbnormalBackgroundBitmap: Bitmap
        private lateinit var mHourBitmap: Bitmap
        private lateinit var mMinuteBitmap: Bitmap
        private lateinit var mHourAmbientBitmap: Bitmap
        private lateinit var mMinuteAmbientBitmap: Bitmap
        private lateinit var mSecondBitmap: Bitmap

        private lateinit var mOriginalHourBitmap: Bitmap
        private lateinit var mOriginalMinuteBitmap: Bitmap
        private lateinit var mOriginalHourAmbientBitmap: Bitmap
        private lateinit var mOriginalMinuteAmbientBitmap: Bitmap
        private lateinit var mOriginalSecondBitmap: Bitmap

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false
        private var mScale: Float = 0F
        private var isDigitalClockOn : Boolean = false
        private var mIsNormalBackground : Boolean = true

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)
        private val mLoopBgList = mutableListOf<Bitmap> (
            BitmapFactory.decodeResource(resources, R.drawable.bg_loop_1),
            BitmapFactory.decodeResource(resources, R.drawable.bg_loop_2),
            BitmapFactory.decodeResource(resources, R.drawable.bg_loop_3),
            BitmapFactory.decodeResource(resources, R.drawable.bg_loop_2)
        )
        private val mOriginalLoopBgList = mutableListOf<Bitmap> (
            BitmapFactory.decodeResource(resources, R.drawable.bg_loop_1),
            BitmapFactory.decodeResource(resources, R.drawable.bg_loop_2),
            BitmapFactory.decodeResource(resources, R.drawable.bg_loop_3),
            BitmapFactory.decodeResource(resources, R.drawable.bg_loop_2)
        )

        private val mTimeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(WatchFaceStyle.Builder(this@MyWatchFace)
                    .setAcceptsTapEvents(true)
                    .build())

            mCalendar = Calendar.getInstance()

            initializeBackground()
            initializeWatchFace()
        }

        private fun initializeBackground() {
            mBackgroundPaint = Paint().apply {
                color = Color.BLACK
            }

            mCriteriaBitmap =
                BitmapFactory.decodeResource(resources, R.drawable.bg_loop_1)

            /* Extracts colors from background image to improve watchface style. */
            Palette.from(mCriteriaBitmap).generate {
                it?.let {
                    mWatchHandHighlightColor = it.getVibrantColor(Color.RED)
                    mWatchHandColor = Color.WHITE
                    mWatchHandShadowColor = it.getDarkMutedColor(Color.BLACK)
                    updateWatchHandStyle()
                }
            }
        }

        private fun initializeWatchFace() {
            /* Set defaults for colors */
            mWatchHandColor = Color.WHITE
            mWatchHandHighlightColor = Color.RED
            mWatchHandShadowColor = Color.BLACK

            mTickAndCirclePaint = Paint().apply {
                color = mWatchHandColor
                strokeWidth = SECOND_TICK_STROKE_WIDTH
                isAntiAlias = true
                style = Paint.Style.STROKE
                setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
            }

            mHourBitmap = BitmapFactory.decodeResource(resources, R.drawable.hourhand)
            mOriginalHourBitmap = BitmapFactory.decodeResource(resources, R.drawable.hourhand)
            mMinuteBitmap = BitmapFactory.decodeResource(resources, R.drawable.minutehand)
            mOriginalMinuteBitmap = BitmapFactory.decodeResource(resources, R.drawable.minutehand)
            mHourAmbientBitmap = BitmapFactory.decodeResource(resources, R.drawable.hourhand_ambient)
            mOriginalHourAmbientBitmap = BitmapFactory.decodeResource(resources, R.drawable.hourhand_ambient)
            mMinuteAmbientBitmap = BitmapFactory.decodeResource(resources, R.drawable.minutehand_ambient)
            mOriginalMinuteAmbientBitmap = BitmapFactory.decodeResource(resources, R.drawable.minutehand_ambient)
            mSecondBitmap = BitmapFactory.decodeResource(resources, R.drawable.secondhand)
            mOriginalSecondBitmap = BitmapFactory.decodeResource(resources, R.drawable.secondhand)
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(
                    WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false)
            mBurnInProtection = properties.getBoolean(
                    WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false)
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode

            updateWatchHandStyle()

            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
        }

        private fun updateWatchHandStyle() {
            if (mAmbient) {
                mTickAndCirclePaint.color = Color.WHITE
                mTickAndCirclePaint.isAntiAlias = false
                mTickAndCirclePaint.clearShadowLayer()
            } else {
                mTickAndCirclePaint.color = mWatchHandColor

                mTickAndCirclePaint.isAntiAlias = true
                mTickAndCirclePaint.setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
            }
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode
                invalidate()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f
            mCenterY = height / 2f

            /* Scale loaded background image (more efficient) if surface dimensions change. */
            mScale = (width.toFloat() / mCriteriaBitmap.width.toFloat())

            /*
             * Create a gray version of the image only if it will look nice on the device in
             * ambient mode. That means we don"t want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren"t
             * selecting their own photos for the watch face), it will be more
             * efficient to create a black/white version (png, etc.) and load that when you need it.
             */
            if (!mBurnInProtection && !mLowBitAmbient) {
                initGrayBackgroundBitmap(mScale)
                initGrayAbnormalBackgroundBitmap(mScale)
            }
        }

        private fun initGrayBackgroundBitmap(scale: Float) {
            mGrayBackgroundBitmap = Bitmap.createBitmap(
                    mCriteriaBitmap.width,
                    mCriteriaBitmap.height,
                    Bitmap.Config.ARGB_8888)
            val canvas = Canvas(mGrayBackgroundBitmap)
            val grayPaint = Paint()
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0f)
            val filter = ColorMatrixColorFilter(colorMatrix)
            grayPaint.colorFilter = filter
            val ambientBitmap =
                BitmapFactory.decodeResource(resources, R.drawable.watchface_service_ambient_bg)
            mAmbientBackgroundBitmap = Bitmap.createScaledBitmap(
                ambientBitmap,
                (ambientBitmap.width * scale).toInt(),
                (ambientBitmap.height * scale).toInt(),
                true
            )
            canvas.drawBitmap(mAmbientBackgroundBitmap, 0f, 0f, grayPaint)
        }

        private fun initGrayAbnormalBackgroundBitmap(scale: Float) {
            mGrayAbnormalBackgroundBitmap = Bitmap.createBitmap(
                mCriteriaBitmap.width,
                mCriteriaBitmap.height,
                Bitmap.Config.ARGB_8888)
            val canvas = Canvas(mGrayAbnormalBackgroundBitmap)
            val grayPaint = Paint()
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0f)
            val filter = ColorMatrixColorFilter(colorMatrix)
            grayPaint.colorFilter = filter
            val ambientBitmap =
                BitmapFactory.decodeResource(resources, R.drawable.watchface_service_ambient_abnormal_bg)
            mAmbientBackgroundBitmapAbnormal = Bitmap.createScaledBitmap(
                ambientBitmap,
                (ambientBitmap.width * scale).toInt(),
                (ambientBitmap.height * scale).toInt(),
                true
            )
            canvas.drawBitmap(mAmbientBackgroundBitmapAbnormal, 0f, 0f, grayPaint)
        }

        /**
         * Captures tap event (and tap type). The [WatchFaceService.TAP_TYPE_TAP] case can be
         * used for implementing specific logic to handle the gesture.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP -> {
                    // The user has completed the tap gesture.
                    if(y > mCenterY) {
                        mIsNormalBackground = !mIsNormalBackground
                        val txt = if (mIsNormalBackground) {
                                "잠금화면 도구리가 \n눈을 감았습니다"
                            } else {
                                "잠금화면 도구리가 \n눈을 떴습니다"
                            }
                        Toast.makeText(applicationContext, txt, Toast.LENGTH_SHORT).show()
                    } else {
                        isDigitalClockOn = !isDigitalClockOn
                    }
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now

            drawBackground(canvas)
            drawWatchFace(canvas)
        }

        private var currentFrameIndex = 0

        private fun drawBackground(canvas: Canvas) {
            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK)
            } else if (mAmbient) {
                canvas.drawColor(Color.BLACK)
                if (mIsNormalBackground) {
                    canvas.drawBitmap(mGrayBackgroundBitmap, 0f, 0f, mBackgroundPaint)
                } else {
                    canvas.drawBitmap(mGrayAbnormalBackgroundBitmap, 0f, 0f, mBackgroundPaint)
                }
            } else {
                if (currentFrameIndex < mLoopBgList.size) {
                    canvas.save()
                    mLoopBgList[currentFrameIndex] = createScaledBitmap(mOriginalLoopBgList[currentFrameIndex], mLoopBgList[currentFrameIndex])
                    canvas.drawBitmap(mLoopBgList[currentFrameIndex], 0f, 0f, mBackgroundPaint)
                    canvas.restore()
                    currentFrameIndex++
                    if (currentFrameIndex >= mLoopBgList.size) {
                        currentFrameIndex = 0
                    }
                }

                if (isDigitalClockOn) {
                    val date = SimpleDateFormat("MM/dd")
                    val time = SimpleDateFormat("hh:mm")
                    val currentDate = date.format(Date())
                    val currentTime = time.format(Date())
                    val paint = Paint()
                    paint.style = Paint.Style.FILL
                    paint.color = (Color.parseColor("#5d332b"))
                    paint.textSize = dpToPx(10F)
                    paint.textAlign = Paint.Align.CENTER
                    paint.typeface = ResourcesCompat.getFont(applicationContext, R.font.hs_uji)
                    canvas.drawText(currentDate, mCenterX, mCenterY + dpToPx(18F), paint)
                    paint.textSize = dpToPx(13F)
                    paint.isAntiAlias = false
                    canvas.drawText(currentTime, mCenterX, mCenterY + dpToPx(30F), paint)
                }
            }
        }

        private fun useCurrentBitmap(originalBitmap: Bitmap, currentBitmap: Bitmap): Boolean {
            return ((originalBitmap.width * mScale).toInt() == currentBitmap.width)
        }

        private fun createScaledBitmap(originalBitmap: Bitmap, currentBitmap: Bitmap): Bitmap {
            return if (useCurrentBitmap(originalBitmap, currentBitmap)) {
                currentBitmap
            } else {
                Bitmap.createScaledBitmap(originalBitmap, (originalBitmap.width * mScale).toInt(), (originalBitmap.height * mScale).toInt(), true)
            }
        }

        private fun dpToPx(dp: Float): Float {
            val density = applicationContext.resources.displayMetrics.densityDpi
            return dp * (density / DisplayMetrics.DENSITY_DEFAULT)
        }

        private fun drawWatchFace(canvas: Canvas) {

            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */
            val innerTickRadius = mCenterX - 10
            val outerTickRadius = mCenterX
            for (tickIndex in 0..11) {
                val tickRot = (tickIndex.toDouble() * Math.PI * 2.0 / 12).toFloat()
                val innerX = Math.sin(tickRot.toDouble()).toFloat() * innerTickRadius
                val innerY = (-Math.cos(tickRot.toDouble())).toFloat() * innerTickRadius
                val outerX = Math.sin(tickRot.toDouble()).toFloat() * outerTickRadius
                val outerY = (-Math.cos(tickRot.toDouble())).toFloat() * outerTickRadius
                canvas.drawLine(mCenterX + innerX, mCenterY + innerY,
                        mCenterX + outerX, mCenterY + outerY, mTickAndCirclePaint)
            }

            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            val seconds =
                    mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f
            val secondsRotation = seconds * 6f

            val minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f

            val hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f
            val hoursRotation = mCalendar.get(Calendar.HOUR) * 30 + hourHandOffset

            /*
             * Save the canvas state before we can begin to rotate it.
             */
            canvas.save()

            var grayPaint : Paint? = Paint()
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0f)
            val filter = ColorMatrixColorFilter(colorMatrix)
            grayPaint?.colorFilter = filter

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            if (!mAmbient) {
                grayPaint = null

                canvas.rotate(hoursRotation, mCenterX, mCenterY)
                mHourBitmap = createScaledBitmap(mOriginalHourBitmap, mHourBitmap)
                canvas.drawBitmap(
                    mHourBitmap,
                    mCenterX - (mHourBitmap.width / 2),
                    mCenterY - mHourBitmap.height,
                    grayPaint
                )

                canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY)
                mMinuteBitmap = createScaledBitmap(mOriginalMinuteBitmap, mMinuteBitmap)
                canvas.drawBitmap(
                    mMinuteBitmap,
                    mCenterX - (mMinuteBitmap.width / 2),
                    mCenterY - mMinuteBitmap.height,
                    grayPaint
                )

                canvas.rotate(secondsRotation - minutesRotation, mCenterX, mCenterY)
                mSecondBitmap = createScaledBitmap(mOriginalSecondBitmap, mSecondBitmap)
                canvas.drawBitmap(
                    mSecondBitmap,
                    mCenterX - (mSecondBitmap.width / 2),
                    mCenterY - mSecondBitmap.height,
                    null
                )

            } else {
                canvas.rotate(hoursRotation, mCenterX, mCenterY)
                mHourAmbientBitmap = createScaledBitmap(mOriginalHourAmbientBitmap, mHourAmbientBitmap)
                canvas.drawBitmap(
                    mHourAmbientBitmap,
                    mCenterX - (mHourAmbientBitmap.width / 2),
                    mCenterY - mHourAmbientBitmap.height,
                    grayPaint
                )

                canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY)
                mMinuteAmbientBitmap = createScaledBitmap(mOriginalMinuteAmbientBitmap, mMinuteAmbientBitmap)
                canvas.drawBitmap(
                    mMinuteAmbientBitmap,
                    mCenterX - (mMinuteAmbientBitmap.width / 2),
                    mCenterY - mMinuteAmbientBitmap.height,
                    grayPaint
                )
                canvas.drawARGB(100, 0, 0, 0)
            }

            /* Restore the canvas" original orientation. */
            canvas.restore()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren"t visible. */
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@MyWatchFace.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@MyWatchFace.unregisterReceiver(mTimeZoneReceiver)
        }

        /**
         * Starts/stops the [.mUpdateTimeHandler] timer based on the state of the watch face.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer
         * should only run in active mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !mAmbient
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }
    }
}