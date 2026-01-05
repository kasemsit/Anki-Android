/*
 * Copyright (c) 2025 Kasemsit Teeyapan <kasemsit@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import timber.log.Timber

/**
 * SurfaceView-based whiteboard for lag-free drawing on Boox e-reader devices.
 * Uses Onyx SDK's TouchHelper to batch touch input and Canvas for rendering.
 *
 * Based on Notable app's architecture:
 * - SurfaceView provides a dedicated drawing surface
 * - TouchHelper batches stylus input from Onyx SDK
 * - Bitmap backing stores the drawing
 * - Canvas API for actual rendering
 */
class WhiteboardSurface
    @JvmOverloads
    constructor(
        context: Context,
        attrs: android.util.AttributeSet? = null,
        defStyleAttr: Int = 0,
        private val inverted: Boolean = false,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {
        // Drawing state
        private var drawingBitmap: Bitmap? = null
        private var drawingCanvas: Canvas? = null

        private val paint =
            Paint().apply {
                isAntiAlias = true
                isDither = true
                color = if (inverted) Color.WHITE else Color.BLACK
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeWidth = 6f
            }

        private var currentPath = Path()
        private var isDrawing = false

        // TouchHelper for Onyx SDK integration
        private var touchHelper: TouchHelper? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var disableTouchHelperRunnable: Runnable? = null

        init {
            holder.addCallback(this)
            setWillNotDraw(false)

            // Use setZOrderMediaOverlay to allow buttons to work
            // Trade-off: whiteboard won't be transparent
            setZOrderMediaOverlay(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }

        // RawInputCallback to receive batched touch points from Onyx SDK
        private val inputCallback =
            object : RawInputCallback() {
                override fun onBeginRawDrawing(
                    p0: Boolean,
                    p1: TouchPoint?,
                ) {
                    Timber.d("onBeginRawDrawing: p0=$p0, point=${p1?.x},${p1?.y}")
                    // Cancel any pending disable
                    disableTouchHelperRunnable?.let { handler.removeCallbacks(it) }
                    if (p1 != null) {
                        currentPath.reset()
                        currentPath.moveTo(p1.x, p1.y)
                        isDrawing = true
                    }
                }

                override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
                    // Not used - we handle batched points in onRawDrawingTouchPointListReceived
                }

                override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) {
                    Timber.d("onRawDrawingTouchPointListReceived: ${plist.points.size} points")
                    if (!isDrawing || plist.points.isEmpty()) return

                    // Add points to path
                    for (point in plist.points) {
                        currentPath.lineTo(point.x, point.y)
                    }

                    // Draw the path to both bitmap and screen
                    drawPath(currentPath)
                }

                override fun onEndRawDrawing(
                    p0: Boolean,
                    p1: TouchPoint?,
                ) {
                    Timber.d("onEndRawDrawing")
                    isDrawing = false
                    currentPath.reset()

                    // Disable TouchHelper after 500ms to allow button clicks
                    disableTouchHelperRunnable =
                        Runnable {
                            Timber.d("Disabling TouchHelper to allow button clicks")
                            touchHelper?.setRawDrawingEnabled(false)
                        }
                    handler.postDelayed(disableTouchHelperRunnable!!, 500)
                }

                override fun onBeginRawErasing(
                    p0: Boolean,
                    p1: TouchPoint?,
                ) {
                    // Erasing not implemented yet
                }

                override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
                    // Not used
                }

                override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) {
                    // Erasing not implemented yet
                }

                override fun onEndRawErasing(
                    p0: Boolean,
                    p1: TouchPoint?,
                ) {
                    // Erasing not implemented yet
                }
            }

        override fun surfaceCreated(holder: SurfaceHolder) {
            // Surface created - TouchHelper will be initialized in surfaceChanged when dimensions are valid
            // Clear the surface to make it transparent
            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            // Create bitmap only if it doesn't exist OR if size actually changed
            val needsNewBitmap =
                drawingBitmap == null ||
                    drawingBitmap!!.width != width ||
                    drawingBitmap!!.height != height

            if (needsNewBitmap) {
                Timber.d("Creating new bitmap: ${width}x$height")
                val oldBitmap = drawingBitmap
                drawingBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawingCanvas = Canvas(drawingBitmap!!)

                // Make bitmap transparent
                drawingBitmap?.eraseColor(Color.TRANSPARENT)

                // If we had an old bitmap, copy its content to preserve drawings
                if (oldBitmap != null && !oldBitmap.isRecycled) {
                    drawingCanvas?.drawBitmap(oldBitmap, 0f, 0f, null)
                    oldBitmap.recycle()
                }
            }

            // Initialize TouchHelper for lag-free drawing on Boox devices
            if (isOnyxDevice() && touchHelper == null && width > 0 && height > 0) {
                Timber.d("Initializing TouchHelper for Boox device")
                initializeTouchHelper()

                val bounds = Rect(0, 0, width, height)
                touchHelper?.setLimitRect(bounds, listOf())
                touchHelper?.openRawDrawing()
                // Start with TouchHelper disabled - it will be enabled on first touch
                touchHelper?.setRawDrawingEnabled(false)
                Timber.d("TouchHelper initialized (disabled by default)")
            }
        }

        private fun initializeTouchHelper() {
            try {
                // Enable EPD optimizations
                EpdController.setViewDefaultUpdateMode(this, UpdateMode.DU)
                EpdController.enableA2ForSpecificView(this)

                // Create TouchHelper
                touchHelper = TouchHelper.create(this, 2, inputCallback)
                touchHelper?.setPenUpRefreshTimeMs(500)
                touchHelper?.setStrokeColor(paint.color)
                touchHelper?.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                touchHelper?.setStrokeWidth(paint.strokeWidth)
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize TouchHelper")
            }
        }

        /**
         * Checks if the current device is an Onyx Boox e-reader.
         */
        private fun isOnyxDevice(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            return manufacturer.contains("onyx") || manufacturer.contains("boox")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            // Cleanup TouchHelper and drawing state
            cleanup()

            // Cleanup bitmap
            drawingBitmap?.recycle()
            drawingBitmap = null
            drawingCanvas = null
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // On Boox devices with TouchHelper, re-enable it when drawing starts
            if (isOnyxDevice() && touchHelper != null) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Timber.d("Re-enabling TouchHelper for drawing")
                    disableTouchHelperRunnable?.let { handler.removeCallbacks(it) }
                    touchHelper?.setRawDrawingEnabled(true)
                }
                // Let TouchHelper handle the event
                return true
            }

            // Fallback for non-Boox devices: regular touch handling
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentPath.reset()
                    currentPath.moveTo(event.x, event.y)
                    isDrawing = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isDrawing) {
                        currentPath.lineTo(event.x, event.y)
                        drawPath(currentPath)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDrawing = false
                    currentPath.reset()
                    return true
                }
            }

            return super.onTouchEvent(event)
        }

        /**
         * Draws a path to both the backing bitmap and the screen surface.
         */
        private fun drawPath(path: Path) {
            // Draw to bitmap
            drawingCanvas?.drawPath(path, paint)

            // Draw to screen
            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    // Clear canvas
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                    // Draw the bitmap
                    drawingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }

        /**
         * Clears the whiteboard.
         */
        fun clear() {
            drawingBitmap?.eraseColor(Color.TRANSPARENT)
            currentPath.reset()

            // Clear the screen
            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }

        /**
         * Sets the drawing color.
         */
        fun setColor(color: Int) {
            paint.color = color
            touchHelper?.setStrokeColor(color)
        }

        /**
         * Sets the stroke width.
         */
        fun setStrokeWidth(width: Float) {
            paint.strokeWidth = width
            touchHelper?.setStrokeWidth(width)
        }

        /**
         * Gets the current drawing as a bitmap.
         */
        fun getBitmap(): Bitmap? = drawingBitmap

        /**
         * Clean up resources when the whiteboard is no longer needed.
         * Call this when hiding the whiteboard or destroying the view.
         */
        fun cleanup() {
            // Cancel any pending disable callbacks
            disableTouchHelperRunnable?.let { handler.removeCallbacks(it) }

            // Cleanup TouchHelper
            try {
                touchHelper?.setRawDrawingEnabled(false)
                touchHelper?.closeRawDrawing()
                touchHelper = null

                if (isOnyxDevice()) {
                    EpdController.disableA2ForSpecificView(this)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup TouchHelper")
            }

            // Clear drawing state
            currentPath.reset()
            isDrawing = false
        }
    }
