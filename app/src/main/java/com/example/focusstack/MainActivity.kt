package com.example.focusstack

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 100
        private const val REQUEST_STORAGE_PERMISSION = 101
        private const val FOCUS_STEP_COUNT = 7
    }

    private lateinit var textureView: TextureView
    private lateinit var resultImageView: ImageView
    private lateinit var progressLayout: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnSave: Button
    private lateinit var btnRetake: Button
    private lateinit var btnCancelCapture: Button

    private lateinit var cameraController: CameraController
    private var lastResultBitmap: Bitmap? = null
    private var isCapturing = false
    private var isSaving = false
    private var resumed = false

    /** 合成专用单线程：保证同一次拍摄的图片按序处理，也防止重复触发合成 */
    private val stackExecutor = Executors.newSingleThreadExecutor()

    /** 保存照片用，避免在 UI 线程压缩/写盘 */
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        resultImageView = findViewById(R.id.resultImageView)
        progressLayout = findViewById(R.id.progressLayout)
        progressText = findViewById(R.id.progressText)
        btnCapture = findViewById(R.id.btnCapture)
        btnSave = findViewById(R.id.btnSave)
        btnRetake = findViewById(R.id.btnRetake)
        btnCancelCapture = findViewById(R.id.btnCancelCapture)

        cameraController = CameraController(this, textureView)
        cameraController.onPreviewReady = { runOnUiThread { applyTransform() } }
        cameraController.onCameraError = { msg -> runOnUiThread { showToast(msg) } }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                applyTransform()
                if (resumed && hasCameraPermission()) startCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        btnCapture.setOnClickListener { startCaptureStack() }
        btnSave.setOnClickListener { saveResult() }
        btnRetake.setOnClickListener { backToPreview() }
        // 用户主动取消：CameraController 会回调 onError("拍摄已取消")，由此统一复位界面
        btnCancelCapture.setOnClickListener { cameraController.cancelStack() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION
            )
            return
        }
        startCamera()
    }

    /** 启动相机（onResume 与相机权限通过后共用，不再手动重入 onResume） */
    private fun startCamera() {
        cameraController.startBackgroundThread()
        if (textureView.isAvailable) {
            applyTransform()
            cameraController.openCamera()
        }
    }

    override fun onPause() {
        resumed = false
        // 若正在拍摄，close() 会回调 onError("拍摄已取消")，由它负责复位界面；
        // 若已在合成阶段（相机侧 listener 已置空），合成线程会自行走完 showResult/fail
        cameraController.close()
        cameraController.stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        stackExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_CAMERA_PERMISSION -> {
                if (granted) {
                    if (resumed) startCamera()
                } else {
                    showToast("需要相机权限才能使用本功能")
                    finish()
                }
            }
            REQUEST_STORAGE_PERMISSION -> {
                if (granted) showToast("已授权，请再点一次保存") else showToast("需要存储权限才能保存照片")
            }
        }
    }

    /** 根据屏幕旋转与传感器方向修正预览显示 */
    private fun applyTransform() {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        cameraController.configureTransform(textureView.width, textureView.height, rotation)
    }

    private fun startCaptureStack() {
        if (isCapturing) return
        isCapturing = true
        btnCapture.isEnabled = false
        progressLayout.visibility = View.VISIBLE
        progressText.text = "正在拍摄第 1/$FOCUS_STEP_COUNT 张，请保持手机静止..."

        val stacker = FocusStacker(cameraController.sensorOrientation)

        cameraController.captureFocusStack(FOCUS_STEP_COUNT, object : CameraController.StackCaptureListener {
            @Volatile
            private var cancelled = false

            override fun onProgress(index: Int, total: Int) {
                runOnUiThread {
                    progressText.text = "正在拍摄第 ${index + 1}/$total 张，请保持手机静止..."
                }
            }

            /** 相机后台线程回调：立刻把这张交给合成线程，与后续拍摄并行处理 */
            override fun onCaptured(jpeg: ByteArray) {
                if (cancelled) return
                executeQuietly(stackExecutor) {
                    try {
                        stacker.addImage(jpeg)
                    } catch (e: OutOfMemoryError) {
                        cancelFlow("内存不足，合成失败，请关闭其他应用后重试")
                    } catch (e: Exception) {
                        cancelFlow("合成失败：${e.message}")
                    }
                }
            }

            override fun onFinished() {
                if (cancelled) return
                runOnUiThread { progressText.text = "拍摄完成，正在生成结果..." }
                executeQuietly(stackExecutor) {
                    try {
                        val result = stacker.finish()
                        runOnUiThread { if (!cancelled) showResult(result) }
                    } catch (e: OutOfMemoryError) {
                        cancelFlow("内存不足，合成失败，请关闭其他应用后重试")
                    } catch (e: Exception) {
                        cancelFlow("合成失败：${e.message}")
                    }
                }
            }

            override fun onError(message: String) {
                cancelled = true // 已中止，丢弃后续拍摄/合成回调
                runOnUiThread { failAndReset(message) }
            }

            /** 合成线程调用：标记取消并复位界面 */
            private fun cancelFlow(message: String) {
                cancelled = true
                runOnUiThread { if (isCapturing) failAndReset(message) }
            }
        })
    }

    /** Executor 已关闭（Activity 销毁）时静默丢弃任务 */
    private fun executeQuietly(executor: ExecutorService, task: Runnable) {
        try {
            if (!executor.isShutdown) executor.execute(task)
        } catch (e: RejectedExecutionException) {
            // Activity 已销毁，丢弃
        }
    }

    private fun failAndReset(message: String) {
        showToast(message)
        resetToPreviewState()
    }

    private fun showResult(bitmap: Bitmap) {
        lastResultBitmap = bitmap
        isCapturing = false
        progressLayout.visibility = View.GONE
        textureView.visibility = View.GONE
        resultImageView.visibility = View.VISIBLE
        resultImageView.setImageBitmap(bitmap)
        btnCapture.visibility = View.GONE
        btnSave.visibility = View.VISIBLE
        btnSave.isEnabled = true
        btnRetake.visibility = View.VISIBLE
    }

    private fun backToPreview() {
        resultImageView.setImageBitmap(null)
        lastResultBitmap = null
        resultImageView.visibility = View.GONE
        textureView.visibility = View.VISIBLE
        btnSave.visibility = View.GONE
        btnRetake.visibility = View.GONE
        resetToPreviewState()
    }

    private fun resetToPreviewState() {
        isCapturing = false
        progressLayout.visibility = View.GONE
        btnCapture.visibility = View.VISIBLE
        btnCapture.isEnabled = true
    }

    private fun saveResult() {
        val bitmap = lastResultBitmap ?: return
        if (isSaving) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission()) {
            // 旧系统先申请存储权限，授权后由用户再点一次保存
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_PERMISSION
            )
            return
        }

        // 保存期间禁用按钮，避免重复点击保存出多张相同照片
        isSaving = true
        btnSave.isEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            executeQuietly(ioExecutor) { saveWithMediaStore(bitmap) }
        } else {
            executeQuietly(ioExecutor) { saveToLegacyFile(bitmap) }
        }
    }

    /** Android 10 及以上：通过 MediaStore 写入 Pictures/FocusStack */
    private fun saveWithMediaStore(bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FocusStack_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FocusStack")
            // 写入完成前标记为 pending，避免相册提前读到半张图
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            showToast("保存失败")
            finishSaving()
            return
        }
        val ok = try {
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: false
        } catch (e: Exception) {
            false
        }
        if (ok) {
            // 写入完成，解除 pending，相册才会正式展示
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            showToast("已保存到相册 / Pictures/FocusStack")
        } else {
            contentResolver.delete(uri, null, null)
            showToast("保存失败")
        }
        finishSaving()
    }

    /** Android 9 及以下：直接写文件并通知相册扫描 */
    private fun saveToLegacyFile(bitmap: Bitmap) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FocusStack")
            dir.mkdirs()
            val file = File(dir, "FocusStack_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            showToast("已保存到相册 / Pictures/FocusStack")
        } catch (e: IOException) {
            showToast("保存失败：${e.message}")
        }
        finishSaving()
    }

    /** 保存结束（成功或失败）后恢复按钮状态 */
    private fun finishSaving() {
        runOnUiThread {
            isSaving = false
            btnSave.isEnabled = true
        }
    }

    private fun showToast(message: String) {
        // 保存/相机回调可能来自后台线程
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
}
