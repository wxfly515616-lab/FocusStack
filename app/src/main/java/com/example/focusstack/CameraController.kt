package com.example.focusstack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.max

/**
 * Camera2 封装 v2：
 * - 拍摄分辨率约 1200 万像素（4:3）
 * - 每个对焦位置等待镜头真正到位（LENS_STATE + LENS_FOCUS_DISTANCE）后再拍
 * - 所有相机操作都在后台线程执行，onPause 时释放相机
 */
class CameraController(
    private val context: Context,
    private val textureView: TextureView
) {
    companion object {
        private const val TAG = "CameraController"
        private const val CAPTURE_TARGET_AREA = 12_000_000L
        private const val PREVIEW_MAX_WIDTH = 1920
        private const val SETTLE_TIMEOUT_MS = 2000L
        private const val MIN_SETTLE_WAIT_MS = 300L
        private const val SETTLE_STABLE_FRAMES = 2
        private const val FOCUS_TOLERANCE = 0.1f
        private const val IMAGE_TIMEOUT_MS = 5000L // 等待 JPEG 数据到达的超时
    }

    interface StackCaptureListener {
        fun onProgress(index: Int, total: Int)

        /** 每拍完一张立刻回调，便于调用方流式合成，无需把所有 JPEG 攒在内存里 */
        fun onCaptured(jpeg: ByteArray)
        fun onFinished()
        fun onError(message: String)
    }

    /** 预览尺寸就绪后回调（在后台线程触发） */
    var onPreviewReady: ((Size) -> Unit)? = null

    /** 相机级别错误（打开失败、无法访问等） */
    var onCameraError: ((String) -> Unit)? = null

    @Volatile
    var sensorOrientation: Int = 90
        private set

    @Volatile
    private var previewSize = Size(1440, 1080)
    private var captureSize = Size(4000, 3000)
    private var minFocusDistance = 0f

    private var cameraDevice: CameraDevice? = null
    private var opening = false // openCamera 到 onOpened 之间防止重复打开
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // 以下状态只在后台线程读写
    private var listener: StackCaptureListener? = null
    private var focusSteps: List<Float> = emptyList()
    private var stepIndex = 0
    private var waitingForSettle = false
    private var settleStableCount = 0
    private var moveStartMs = 0L

    private val settleTimeoutRunnable = Runnable {
        if (waitingForSettle) triggerStillCapture()
    }

    /** 拍摄请求已发出但 ImageReader 一直没来图时兜底，避免流程永久卡死 */
    private val imageTimeoutRunnable = Runnable {
        if (listener != null) fail("第 ${stepIndex + 1} 张图像数据超时")
    }

    fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止后台线程失败", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    fun openCamera() {
        backgroundHandler?.post {
            // onOpened 之前 cameraDevice 还是 null，必须用 opening 挡住第二次调用
            if (cameraDevice != null || opening) return@post
            opening = true
            openCameraInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal() {
        if (cameraDevice != null) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val id = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            if (id == null) {
                opening = false
                onCameraError?.invoke("未找到后置摄像头")
                return
            }
            val chars = manager.getCameraCharacteristics(id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                opening = false
                onCameraError?.invoke("无法读取相机参数")
                return
            }
            captureSize = pickCaptureSize(map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty())
            previewSize = pickPreviewSize(
                (map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray<Size>()).toList()
            )

            imageReader = ImageReader.newInstance(
                captureSize.width, captureSize.height, ImageFormat.JPEG, 2
            ).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage()
                    if (image != null) onImageCaptured(image)
                }, backgroundHandler)
            }

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!opening) {
                        // 打开过程中被 close()（例如切后台），丢弃这台相机，避免后台占用
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    opening = false
                    createPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    opening = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    opening = false
                    onCameraError?.invoke("相机打开失败，错误码 $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            opening = false
            onCameraError?.invoke("无法访问相机：${e.message}")
        }
    }

    /** 选 4:3 且面积最接近目标值的拍照尺寸 */
    private fun pickCaptureSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(4000, 3000)
        val fourThree = sizes.filter { it.width * 3 == it.height * 4 }
        val pool = if (fourThree.isNotEmpty()) fourThree else sizes
        return pool.minByOrNull { abs(it.width.toLong() * it.height - CAPTURE_TARGET_AREA) } ?: sizes[0]
    }

    /** 预览用 4:3 且不超过 1920 宽的最大尺寸 */
    private fun pickPreviewSize(sizes: List<Size>): Size {
        return sizes
            .filter { it.width * 3 == it.height * 4 && it.width <= PREVIEW_MAX_WIDTH }
            .maxByOrNull { it.width }
            ?: Size(1440, 1080)
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val texture: SurfaceTexture = textureView.surfaceTexture ?: return
        val reader = imageReader ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)
        previewSurface = surface

        device.createCaptureSession(
            listOf(surface, reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview()
                    onPreviewReady?.invoke(previewSize)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onCameraError?.invoke("相机会话配置失败")
                }
            },
            backgroundHandler
        )
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            session.setRepeatingRequest(request.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "预览启动失败", e)
        }
    }

    /** 对外入口：开始连续拍摄 stepCount 张不同对焦距离的照片 */
    fun captureFocusStack(stepCount: Int, listener: StackCaptureListener) {
        val handler = backgroundHandler
        if (handler == null) {
            listener.onError("相机未就绪，请稍后重试")
            return
        }
        handler.post { startStack(stepCount, listener) }
    }

    private fun startStack(stepCount: Int, l: StackCaptureListener) {
        if (captureSession == null || cameraDevice == null) {
            l.onError("相机未就绪，请稍后重试")
            return
        }
        if (minFocusDistance <= 0f) {
            l.onError("该机型不支持手动对焦距离控制")
            return
        }
        listener = l
        stepIndex = 0
        val n = stepCount.coerceAtLeast(2)
        focusSteps = (0 until n).map { minFocusDistance * it / (n - 1) }
        moveToStep()
    }

    private fun moveToStep() {
        if (stepIndex >= focusSteps.size) {
            finishStack()
            return
        }
        val session = captureSession
        val device = cameraDevice
        val surface = previewSurface
        if (session == null || device == null || surface == null) {
            fail("相机会话已断开")
            return
        }
        listener?.onProgress(stepIndex, focusSteps.size)
        waitingForSettle = true
        settleStableCount = 0
        moveStartMs = System.currentTimeMillis()
        try {
            // 用重复预览请求把镜头移动到目标对焦距离，同时监听是否到位
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focusSteps[stepIndex])
            }
            session.setRepeatingRequest(request.build(), focusCallback, backgroundHandler)
            backgroundHandler?.postDelayed(settleTimeoutRunnable, SETTLE_TIMEOUT_MS)
        } catch (e: CameraAccessException) {
            fail("对焦移动失败：${e.message}")
        }
    }

    private val focusCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (!waitingForSettle) return
            val target = focusSteps.getOrNull(stepIndex) ?: return
            val reported = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val state = result.get(CaptureResult.LENS_STATE)
            val reached = reported == null || abs(reported - target) <= FOCUS_TOLERANCE
            val notMoving = state == null || state != CaptureResult.LENS_STATE_MOVING
            val waitedEnough = System.currentTimeMillis() - moveStartMs >= MIN_SETTLE_WAIT_MS

            settleStableCount = if (reached && notMoving && waitedEnough) settleStableCount + 1 else 0
            if (settleStableCount >= SETTLE_STABLE_FRAMES) triggerStillCapture()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (waitingForSettle) fail("对焦预览失败")
        }
    }

    private fun triggerStillCapture() {
        if (!waitingForSettle) return
        waitingForSettle = false
        backgroundHandler?.removeCallbacks(settleTimeoutRunnable)

        val session = captureSession
        val device = cameraDevice
        val reader = imageReader
        if (session == null || device == null || reader == null) {
            fail("相机会话已断开")
            return
        }
        try {
            session.stopRepeating()
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focusSteps[stepIndex])
                set(CaptureRequest.JPEG_ORIENTATION, 0) // 旋转在合成后统一处理
            }
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    fail("第 ${stepIndex + 1} 张拍摄失败")
                }
            }, backgroundHandler)
            // 拍摄请求已受理，给 ImageReader 来图留一个兜底超时
            backgroundHandler?.postDelayed(imageTimeoutRunnable, IMAGE_TIMEOUT_MS)
        } catch (e: Exception) {
            // stopRepeating() 在会话已关闭时可能抛 IllegalStateException，一并兜底
            Log.e(TAG, "拍摄请求失败", e)
            fail("拍摄请求失败：${e.message}")
        }
    }

    private fun onImageCaptured(image: Image) {
        backgroundHandler?.removeCallbacks(imageTimeoutRunnable)
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        image.close()

        val l = listener ?: return // 已经失败或取消，丢弃
        stepIndex++
        l.onCaptured(bytes) // 立刻交给调用方流式合成，不攒在内存里
        moveToStep()
    }

    private fun finishStack() {
        val l = listener ?: return
        listener = null
        startPreview()
        l.onFinished()
    }

    private fun fail(message: String) {
        waitingForSettle = false
        backgroundHandler?.removeCallbacks(settleTimeoutRunnable)
        backgroundHandler?.removeCallbacks(imageTimeoutRunnable)
        val l = listener
        listener = null
        startPreview()
        if (l != null) l.onError(message) else onCameraError?.invoke(message)
    }

    /**
     * 根据屏幕旋转和传感器方向，修正 TextureView 的显示方向与比例。
     * 对 0/90/180/270 所有角度统一做"保持宽高比 + 中心裁切填满"，避免预览被拉伸变形。
     * displayRotation 为 Surface.ROTATION_* 常量（0/1/2/3）。
     */
    fun configureTransform(viewWidth: Int, viewHeight: Int, displayRotation: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val pw = previewSize.width.toFloat()
        val ph = previewSize.height.toFloat()
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val cx = vw / 2f
        val cy = vh / 2f

        val displayDegrees = displayRotation * 90
        val angle = ((sensorOrientation - displayDegrees) % 360 + 360) % 360
        val rotated = angle == 90 || angle == 270

        // 旋转前应有的最小覆盖矩形（保持缓冲区宽高比），旋转后正好填满整个视图
        val w: Float
        val h: Float
        if (rotated) {
            w = max(vh, vw * pw / ph)
            h = w * ph / pw
        } else {
            w = max(vw, vh * pw / ph)
            h = w * ph / pw
        }

        val matrix = Matrix()
        matrix.setRectToRect(
            RectF(0f, 0f, vw, vh),
            RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
            Matrix.ScaleToFit.FILL
        )
        if (angle != 0) matrix.postRotate(angle.toFloat(), cx, cy)
        textureView.setTransform(matrix)
    }

    fun close() {
        val handler = backgroundHandler
        if (handler != null) handler.post { closeInternal() } else closeInternal()
    }

    private fun closeInternal() {
        waitingForSettle = false
        opening = false
        backgroundHandler?.removeCallbacks(settleTimeoutRunnable)
        backgroundHandler?.removeCallbacks(imageTimeoutRunnable)
        listener?.onError("拍摄已取消")
        listener = null
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭会话失败", e)
        }
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
    }
}
