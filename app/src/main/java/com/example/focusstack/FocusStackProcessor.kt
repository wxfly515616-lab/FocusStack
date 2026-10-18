package com.example.focusstack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 景深合成内核（流式 + 并行）：
 * 1. 逐张喂入 JPEG：每张降采样解码（约 1200 万 → 约 300 万像素），
 *    算完立刻释放，可以与拍摄并行，内存里始终只有一张工作图
 * 2. 每张照片计算拉普拉斯边缘强度（越清晰边缘越强），再做箱式模糊平滑
 * 3. 逐像素按清晰度「加权融合」：该位置哪张越清晰，哪张占的比重就越大
 *
 * 三个关键处理，决定了成片是否清晰、是否干净：
 *
 * a) 权重 = 1 + (清晰度 / 本张平均清晰度)^12
 *    指数必须足够陡：白纸小字这类低对比度主体，清晰帧的得分往往只比虚化帧高
 *    一两倍，指数太小（比如 4）会让虚化帧按比例混进来，整块发软。
 *    同时用「本张自己的平均清晰度」做归一化，抵消各帧曝光/对比度差异；
 *    用全局基准或逐帧分位数都是错的（会让早期帧权重被放大几十倍）。
 *
 * b) 逐帧按通道均值把亮度/白平衡对齐到第一张：连续对焦时相机的自动曝光、
 *    自动白平衡会在帧间漂移，不对齐的话融合结果会留下块状亮度/色偏痕迹。
 *
 * c) 用「加权融合」而不是「逐像素挑最清晰的一张」：手机镜头在变焦时视场角会有
 *    约 1% 的变化（呼吸效应），各帧并不完全重合，逐像素硬挑会在高对比度边缘
 *    撕出锯齿状白斑。加权融合的过渡是连续的，天然没有这种拼接痕迹。
 *
 * 内部优化：
 * - 灰度只计算一次并缓存为 ByteArray，拉普拉斯不再每像素重复算 5 次灰度
 * - 箱式模糊改用滚动求和，复杂度从 O(w·h·r) 降为 O(w·h)
 * - 灰度 / 拉普拉斯 / 模糊 / 融合四段都按行分块多线程并行
 */
class FocusStacker(private val rotateDegrees: Int) {

    companion object {
        private const val TAG = "FocusStacker"
        private const val WORK_SAMPLE_SIZE = 2    // 解码降采样倍数
        private const val SMOOTH_RADIUS = 3       // 清晰度得分平滑半径（太大会削弱清晰度区分）
        private const val WEIGHT_GAMMA = 12f      // 权重指数（越大越接近「只挑最清晰那张」）
        private const val MIN_PARALLEL_ROWS = 256 // 行数太少时不开多线程
        private val THREAD_COUNT = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        private val pool = Executors.newFixedThreadPool(THREAD_COUNT) { r ->
            Thread(r, "FocusStack-pool").also { it.isDaemon = true }
        }
    }

    private var width = 0
    private var height = 0
    private var imageCount = 0
    private var pixels = IntArray(0)
    private var gray = ByteArray(0)
    private var score = FloatArray(0)
    private var tmp = FloatArray(0)

    // 逐帧亮度/白平衡对齐：以第一张为基准，后续照片按通道均值做增益补偿
    private var refMeans = floatArrayOf(0f, 0f, 0f)

    // 加权融合累加器：颜色加权和 + 权重和（流式逐张累加，最后一步归一化）
    private var sumR = FloatArray(0)
    private var sumG = FloatArray(0)
    private var sumB = FloatArray(0)
    private var weightT = FloatArray(0)

    /** 相机连拍的一张 JPEG（不做 EXIF 旋转，方向由 finish(rotateDegrees) 统一处理） */
    fun addImage(bytes: ByteArray) {
        val bmp = decodeWithBitmapFactory(bytes) ?: return
        consume(bmp)
    }

    /**
     * 外部导入的一张照片。用 ImageDecoder 解码，会自动应用 EXIF 方向把照片摆正，
     * 因此使用方应传入 rotateDegrees = 0。
     */
    fun addExternalImage(bytes: ByteArray) {
        val bmp = decodeExternal(bytes) ?: return
        consume(bmp)
    }

    private fun consume(decoded: Bitmap) {
        val bmp = fitToWorkSize(decoded)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        imageCount++
        mergeOneImage()
    }

    /** 第一张决定工作尺寸；后续素材尺寸不一致时等比缩放到该尺寸，避免被丢弃 */
    private fun fitToWorkSize(decoded: Bitmap): Bitmap {
        if (width == 0) {
            width = decoded.width
            height = decoded.height
            val n = width * height
            pixels = IntArray(n)
            gray = ByteArray(n)
            score = FloatArray(n)
            tmp = FloatArray(n)
            sumR = FloatArray(n)
            sumG = FloatArray(n)
            sumB = FloatArray(n)
            weightT = FloatArray(n)
            return decoded
        }
        if (decoded.width == width && decoded.height == height) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun decodeWithBitmapFactory(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = WORK_SAMPLE_SIZE
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            Log.e(TAG, "解码失败", e)
            null
        }
    }

    private fun decodeExternal(bytes: ByteArray): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSampleSize(WORK_SAMPLE_SIZE)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                decodeWithBitmapFactory(bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "外部照片解码失败", e)
            null
        }
    }

    /** 所有照片处理完毕，输出合成结果 */
    fun finish(): Bitmap {
        if (imageCount == 0) throw IllegalStateException("照片解码失败")
        // 用累加的颜色和除以权重和得到最终像素
        val n = width * height
        val out = IntArray(n)
        parallelRows(height) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * width
                for (x in 0 until width) {
                    val idx = row + x
                    val t = max(weightT[idx], 1e-6f)
                    val r = (sumR[idx] / t + 0.5f).toInt().coerceIn(0, 255)
                    val g = (sumG[idx] / t + 0.5f).toInt().coerceIn(0, 255)
                    val b = (sumB[idx] / t + 0.5f).toInt().coerceIn(0, 255)
                    out[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        val stacked = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        if (rotateDegrees % 360 == 0) return stacked
        val matrix = Matrix().apply { postRotate(rotateDegrees.toFloat()) }
        return Bitmap.createBitmap(stacked, 0, 0, width, height, matrix, true)
    }

    /** 对刚解码完的一张图：计算清晰度并按权重并入结果 */
    private fun mergeOneImage() {
        val w = width
        val h = height

        // 1. 灰度缓存（每张图只算一次）
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val p = pixels[row + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    // toByte() 会对 >127 的值截断成负数，下一步用 and 0xFF 恢复为 0~255
                    gray[row + x] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                }
            }
        }

        // 2. 拉普拉斯边缘强度
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val idx = row + x
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                        score[idx] = 0f
                        continue
                    }
                    val c = gray[idx].toInt() and 0xFF
                    val lap = 4 * c -
                        (gray[idx - 1].toInt() and 0xFF) - (gray[idx + 1].toInt() and 0xFF) -
                        (gray[idx - w].toInt() and 0xFF) - (gray[idx + w].toInt() and 0xFF)
                    score[idx] = abs(lap).toFloat()
                }
            }
        }

        // 3. 两向箱式模糊平滑（滚动求和）
        parallelRows(h) { y0, y1 -> blurHorizontal(score, tmp, w, SMOOTH_RADIUS, y0, y1) }
        parallelRows(h) { y0, y1 -> blurVertical(tmp, score, w, h, SMOOTH_RADIUS, y0, y1) }

        // 4. 用本张的平均清晰度做归一化基准（抵消各帧曝光/对比度差异），
        //    再按通道增益把这一张的亮度/白平衡对齐到第一张
        val basis = meanOf(score).coerceAtLeast(1f)
        val means = channelMeans()
        if (imageCount <= 1) refMeans = means
        val gainR = if (means[0] > 1f) refMeans[0] / means[0] else 1f
        val gainG = if (means[1] > 1f) refMeans[1] / means[1] else 1f
        val gainB = if (means[2] > 1f) refMeans[2] / means[2] else 1f

        // 5. 加权累加
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val idx = row + x
                    val r0 = score[idx] / basis
                    val r2 = r0 * r0
                    val r4 = r2 * r2
                    val r8 = r4 * r4
                    // (score/basis)^12 = r8 * r4
                    val weight = 1f + r8 * r4
                    val p = pixels[idx]
                    val cr = (((p shr 16) and 0xFF) * gainR).coerceIn(0f, 255f)
                    val cg = (((p shr 8) and 0xFF) * gainG).coerceIn(0f, 255f)
                    val cb = ((p and 0xFF) * gainB).coerceIn(0f, 255f)
                    weightT[idx] += weight
                    sumR[idx] += cr * weight
                    sumG[idx] += cg * weight
                    sumB[idx] += cb * weight
                }
            }
        }
    }

    /** 采样求这张照片 R/G/B 三个通道的平均值（用于亮度/白平衡对齐） */
    private fun channelMeans(): FloatArray {
        val n = pixels.size
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var count = 0
        var i = 0
        while (i < n) {
            val p = pixels[i]
            sr += (p shr 16) and 0xFF
            sg += (p shr 8) and 0xFF
            sb += p and 0xFF
            count++
            i += 128
        }
        val c = count.coerceAtLeast(1).toDouble()
        return floatArrayOf((sr / c).toFloat(), (sg / c).toFloat(), (sb / c).toFloat())
    }

    /** 采样求平均（每 128 个取一个，足够估计整体水平且很快） */
    private fun meanOf(v: FloatArray): Float {
        val n = v.size
        var sum = 0.0
        var i = 0
        while (i < n) {
            sum += v[i]
            i += 128
        }
        return (sum / (n / 128.0)).toFloat()
    }

    /** 把行区间 [0, h) 切给线程池并行执行，工作线程的异常会重新抛出 */
    private fun parallelRows(h: Int, body: (y0: Int, y1: Int) -> Unit) {
        if (h < MIN_PARALLEL_ROWS) {
            body(0, h)
            return
        }
        val per = (h + THREAD_COUNT - 1) / THREAD_COUNT
        val futures = ArrayList<Future<*>>(THREAD_COUNT)
        for (t in 0 until THREAD_COUNT) {
            val y0 = t * per
            val y1 = min(h, y0 + per)
            if (y0 >= y1) break
            futures.add(pool.submit(Runnable { body(y0, y1) }))
        }
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    /** 滚动求和的水平箱式模糊（行内独立，可按行分块） */
    private fun blurHorizontal(
        src: FloatArray, dst: FloatArray, w: Int, r: Int, y0: Int, y1: Int
    ) {
        for (y in y0 until y1) {
            val row = y * w
            var sum = 0f
            for (xx in 0..min(r, w - 1)) sum += src[row + xx]
            for (x in 0 until w) {
                val count = min(x + r, w - 1) - max(x - r, 0) + 1
                dst[row + x] = sum / count
                val add = x + r + 1
                if (add < w) sum += src[row + add]
                val sub = x - r
                if (sub >= 0) sum -= src[row + sub]
            }
        }
    }

    /** 滚动求和的垂直箱式模糊（列内滑动窗口，分块时各自初始化窗口） */
    private fun blurVertical(
        src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, y0: Int, y1: Int
    ) {
        for (x in 0 until w) {
            var sum = 0f
            for (yy in max(0, y0 - r)..min(h - 1, y0 + r)) sum += src[yy * w + x]
            for (y in y0 until y1) {
                val count = min(y + r, h - 1) - max(y - r, 0) + 1
                dst[y * w + x] = sum / count
                val add = y + r + 1
                if (add < h) sum += src[add * w + x]
                val sub = y - r
                if (sub >= 0) sum -= src[sub * w + x]
            }
        }
    }
}
