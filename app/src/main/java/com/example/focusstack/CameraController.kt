package com.example.focusstack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
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
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.min

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
        private const val SETTLE_STABLE_FRAMES = 2
        private const val FOCUS_TOLERANCE = 0.1f
        private const val IMAGE_TIMEOUT_MS = 5000L // 等待 JPEG 数据到达的超时
        private const val AF_LOCK_TIMEOUT_MS = 3000L // 自动对焦锁定超时，超时后退回整段扫描

        /**
         * 主体对焦距离前后各步的步长（屈光度），= 主体对焦距离 / 4。
         * 前后各 3 步大致覆盖「比主体近一半」到「比主体远约 4 倍 / 无限远」的范围。
         */
        private const val DIOPTER_STEP_FACTOR = 1f / 4f

        // 点按对焦
        private const val TAP_FOCUS_TIMEOUT_MS = 2500L   // 点按后等对焦锁定的超时
        private const val TAP_FOCUS_STABLE_FRAMES = 2    // 连续几帧「已锁定且镜头不动」算锁定
        private const val TAP_FOCUS_REGION_FRACTION = 0.2f // 对焦区域取传感器短边的 20%
    }

    interface StackCaptureListener {
        fun onProgress(index: Int, total: Int)

        /** 每拍完一张立刻回调，便于调用方流式合成，无需把所有 JPEG 攒在内存里 */
        fun onCaptured(jpeg: ByteArray)
        fun onFinished()
        fun onError(message: String)

        /** 与拍摄数字无关的阶段提示（如「正在自动对焦锁定…」） */
        fun onHint(message: String)
    }

    /** 相机级别错误（打开失败、无法访问等） */
    var onCameraError: ((String) -> Unit)? = null

    @Volatile
    var sensorOrientation: Int = 90
        private set

    @Volatile
    private var previewSize = Size(1440, 1080)
    private var captureSize = Size(4000, 3000)
    private var minFocusDistance = 0f
    private var activeArray: Rect? = null

    /**
     * 点按对焦锁定的「第一焦点」对焦距离（屈光度，1/米）。
     * 0 表示还没有锁定过，此时「拍摄合成」会自动对焦后再前后各拍 3 张。
     */
    @Volatile
    var lockedDiopter: Float = 0f
        private set

    /** 点按对焦结果回调（true=已锁定）。在后台线程触发，UI 需自行切回主线程。 */
    var onTapFocusResult: ((Boolean) -> Unit)? = null

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
    private var afLocking = false
    private var pendingStepCount = 7

    /** 自动对焦锁定太久没结果时兜底：退回旧的整段扫描，保证流程一定走完 */
    private val afTimeoutRunnable = object : Runnable {
        override fun run() {
            if (!afLocking) return
            afLocking = false
            startSweepAround(0f)
        }
    }

    // 点按对焦状态（只在后台线程读写）
    private var tapFocusing = false
    private var tapFocusStable = 0
    private val tapFocusTimeoutRunnable = object : Runnable {
        override fun run() {
            if (!tapFocusing) return
            tapFocusing = false
            onTapFocusResult?.invoke(false)
        }
    }

    /**
     * 用户点按预览：在点击位置做一次自动对焦并锁定，作为景深合成的「第一焦点」。
     * 参数为相对预览视图的像素坐标。
     */
    fun tapToFocus(viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) {
        backgroundHandler?.post { doTapToFocus(viewX, viewY, viewWidth, viewHeight) }
    }

    /** 清除已锁定的第一焦点（重新打开相机、或用户重新点按前调用） */
    fun clearLockedFocus() {
        lockedDiopter = 0f
    }

    private fun doTapToFocus(vx: Float, vy: Float, vw: Int, vh: Int) {
        val session = captureSession
        val device = cameraDevice
        val surface = previewSurface
        if (session == null || device == null || surface == null) return
        if (vw <= 0 || vh <= 0) return
        if (listener != null || afLocking) return // 拍摄/对焦锁定进行中，忽略点按
        val rect = viewToSensorRect(vx, vy, vw, vh)
        Log.d(TAG, "点按对焦：view=($vx,$vy)/${vw}x$vh region=$rect")
        try {
            val base = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
            }
            tapFocusing = true
            tapFocusStable = 0
            // 先让预览按这个测光/对焦区域跑起来，再单发一次 AF 触发开始扫描对焦
            session.setRepeatingRequest(base.build(), tapFocusCallback, backgroundHandler)
            val trigger = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            session.capture(trigger.build(), tapFocusCallback, backgroundHandler)
            backgroundHandler?.postDelayed(tapFocusTimeoutRunnable, TAP_FOCUS_TIMEOUT_MS)
        } catch (e: CameraAccessException) {
            tapFocusing = false
            Log.e(TAG, "点按对焦失败", e)
            onTapFocusResult?.invoke(false)
        }
    }

    private val tapFocusCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (!tapFocusing) return
            val state = result.get(CaptureResult.CONTROL_AF_STATE)
            val lensState = result.get(CaptureResult.LENS_STATE)
            val done = state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            val notMoving = lensState == null || lensState != CaptureResult.LENS_STATE_MOVING
            tapFocusStable = if (done && notMoving) tapFocusStable + 1 else 0
            if (tapFocusStable >= TAP_FOCUS_STABLE_FRAMES) {
                tapFocusing = false
                backgroundHandler?.removeCallbacks(tapFocusTimeoutRunnable)
                lockedDiopter = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
                Log.d(TAG, "点按对焦锁定：屈光度=$lockedDiopter（0 表示对到无限远）")
                onTapFocusResult?.invoke(true)
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (tapFocusing) {
                tapFocusing = false
                backgroundHandler?.removeCallbacks(tapFocusTimeoutRunnable)
                onTapFocusResult?.invoke(false)
            }
        }
    }

    /**
     * 把预览视图上的点换算成传感器的对焦区域（MeteringRectangle）。
     *
     * 本机预览缓冲是已经摆正的竖幅画面、直接铺满预览视图，所以：
     * 1) 视图坐标 → 0~1 归一化；
     * 2) 竖幅画面相对（横置的）传感器 = 旋转 sensorOrientation 并做了中心裁切，先反裁切再反旋转；
     * 3) 乘上传感器有效阵列尺寸，得到区域的像素范围。
     */
    private fun viewToSensorRect(vx: Float, vy: Float, vw: Int, vh: Int): MeteringRectangle {
        val aw = activeArray?.width() ?: 4000
        val ah = activeArray?.height() ?: 3000
        var nx = (vx / vw).coerceIn(0f, 1f)
        var ny = (vy / vh).coerceIn(0f, 1f)

        // 旋转后画面的宽高比（竖幅）与预览视图的宽高比不同 → 中心裁切
        val rotatedAspect = ah.toFloat() / aw.toFloat()
        val viewAspect = vw.toFloat() / vh.toFloat()
        if (viewAspect < rotatedAspect) {
            nx = 0.5f + (nx - 0.5f) * (viewAspect / rotatedAspect)
        } else if (viewAspect > rotatedAspect) {
            ny = 0.5f + (ny - 0.5f) * (rotatedAspect / viewAspect)
        }

        // 反旋转：画面坐标 → 传感器坐标
        val sx: Float
        val sy: Float
        when (sensorOrientation % 360) {
            90 -> {
                sx = ny; sy = 1f - nx
            }
            270 -> {
                sx = 1f - ny; sy = nx
            }
            180 -> {
                sx = 1f - nx; sy = 1f - ny
            }
            else -> {
                sx = nx; sy = ny
            }
        }

        val side = (min(aw, ah) * TAP_FOCUS_REGION_FRACTION).toInt().coerceAtLeast(1)
        val cx = (sx * aw).toInt()
        val cy = (sy * ah).toInt()
        // 有效阵列可能不从 (0,0) 开始，加上偏移才是传感器坐标系里的绝对位置
        val ox = activeArray?.left ?: 0
        val oy = activeArray?.top ?: 0
        val left = (ox + cx - side / 2).coerceIn(ox, (ox + aw - side).coerceAtLeast(ox))
        val top = (oy + cy - side / 2).coerceIn(oy, (oy + ah - side).coerceAtLeast(oy))
        return MeteringRectangle(left, top, side, side, MeteringRectangle.METERING_WEIGHT_MAX)
    }

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
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

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

    /** 预览用 4:3 且不超过 1920 宽的最大尺寸（保证一定来自设备支持列表，避免比例不符导致预览变形） */
    private fun pickPreviewSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(1440, 1080)
        val limited = sizes.filter { it.width <= PREVIEW_MAX_WIDTH }
        val fourThree = limited.filter { it.width * 3 == it.height * 4 }
        val picked = fourThree.maxByOrNull { it.width }
            ?: limited.maxByOrNull { it.width }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }!!
        Log.d(TAG, "预览尺寸选定：${picked.width}x${picked.height}（支持 4:3：${fourThree.isNotEmpty()}）")
        return picked
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
        pendingStepCount = stepCount
        stepIndex = 0
        val locked = lockedDiopter
        if (locked > 0f) {
            // 用户已经点屏幕锁定了「第一焦点」，直接以它为中心前后各拍几张
            Log.d(TAG, "使用已锁定的第一焦点：屈光度=$locked")
            startSweepAround(locked)
        } else {
            lockFocusFirst()
        }
    }

    /** 第一步：用自动对焦把镜头锁定在主体上，之后以它为中心前后各多拍几张 */
    private fun lockFocusFirst() {
        val session = captureSession
        val device = cameraDevice
        val surface = previewSurface
        if (session == null || device == null || surface == null) {
            fail("相机会话已断开")
            return
        }
        afLocking = true
        settleStableCount = 0
        listener?.onHint("正在自动对焦锁定主体，请保持不动...")
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            session.setRepeatingRequest(request.build(), afLockCallback, backgroundHandler)
            backgroundHandler?.postDelayed(afTimeoutRunnable, AF_LOCK_TIMEOUT_MS)
        } catch (e: CameraAccessException) {
            fail("对焦锁定失败：${e.message}")
        }
    }

    /** 对焦锁定期间监听 AF_STATE，锁定且镜头停稳后探测实际对焦距离 */
    private val afLockCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (!afLocking) return
            val state = result.get(CaptureResult.CONTROL_AF_STATE)
            val lensState = result.get(CaptureResult.LENS_STATE)
            val focused = state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
            val notMoving = lensState == null || lensState != CaptureResult.LENS_STATE_MOVING
            settleStableCount = if (focused && notMoving) settleStableCount + 1 else 0
            if (settleStableCount >= SETTLE_STABLE_FRAMES) {
                afLocking = false
                backgroundHandler?.removeCallbacks(afTimeoutRunnable)
                probeFocusDistance()
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (afLocking) fail("对焦锁定失败")
        }
    }

    /** AF 模式下有些机型不报屈光度，单独发一发 AF_MODE_OFF 的请求来读镜头实际位置 */
    private fun probeFocusDistance() {
        val session = captureSession
        val device = cameraDevice
        val surface = previewSurface
        if (session == null || device == null || surface == null) {
            fail("相机会话已断开")
            return
        }
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            }
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (listener == null) return
                    startSweepAround(result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f)
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            fail("读取对焦距离失败：${e.message}")
        }
    }

    /**
     * 以已锁定的对焦距离（屈光度）为中心，前后各 half 张；
     * 拿不到对焦距离时退回旧的「最近到无限远」整段扫描。
     */
    private fun startSweepAround(lockedDiopter: Float) {
        val n = pendingStepCount.coerceAtLeast(2)
        val half = (n - 1) / 2
        focusSteps = if (half == 0 || lockedDiopter <= 0f) {
            (0 until n).map { minFocusDistance * it / (n - 1) }
        } else {
            val step = lockedDiopter * DIOPTER_STEP_FACTOR
            (-half..half).map { lockedDiopter + it * step }
                .map { it.coerceIn(0f, minFocusDistance) }
                .distinct()
        }
        if (focusSteps.size < 2) {
            focusSteps = (0 until n).map { minFocusDistance * it / (n - 1) }
        }
        Log.d(TAG, "扫焦序列（屈光度）=$focusSteps")
        stepIndex = 0
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

            settleStableCount = if (reached && notMoving) settleStableCount + 1 else 0
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
        buffer.get(bytes) // 必须先把 JPEG 数据读出来，再归还 Image
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
        afLocking = false
        backgroundHandler?.removeCallbacks(settleTimeoutRunnable)
        backgroundHandler?.removeCallbacks(imageTimeoutRunnable)
        backgroundHandler?.removeCallbacks(afTimeoutRunnable)
        val l = listener
        listener = null
        startPreview()
        if (l != null) l.onError(message) else onCameraError?.invoke(message)
    }

    /** 用户主动取消：仅当确实处于拍摄流程时才中断并恢复预览 */
    fun cancelStack() {
        val handler = backgroundHandler ?: return
        handler.post { if (listener != null) fail("拍摄已取消") }
    }

    /**
     * 本机 HAL 输出的预览缓冲已经是摆正后的竖幅画面（实测不加任何变换时
     * 预览方向正确、无拉伸），所以不再对 TextureView 做 setTransform。
     */

    fun close() {
        val handler = backgroundHandler
        if (handler != null) handler.post { closeInternal() } else closeInternal()
    }

    private fun closeInternal() {
        waitingForSettle = false
        afLocking = false
        tapFocusing = false
        lockedDiopter = 0f
        opening = false
        backgroundHandler?.removeCallbacks(settleTimeoutRunnable)
        backgroundHandler?.removeCallbacks(imageTimeoutRunnable)
        backgroundHandler?.removeCallbacks(afTimeoutRunnable)
        backgroundHandler?.removeCallbacks(tapFocusTimeoutRunnable)
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
