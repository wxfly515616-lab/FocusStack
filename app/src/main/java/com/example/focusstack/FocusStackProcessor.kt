package com.example.focusstack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
 * 2. 每张照片计算拉普拉斯边缘强度（越清晰边缘越强），并做箱式模糊平滑
 * 3. 逐像素比较：哪张照片在该位置的清晰度最高，就取哪张的像素（最大清晰度选择法）
 *
 * 内部优化：
 * - 灰度只计算一次并缓存为 ByteArray，拉普拉斯不再每像素重复算 5 次灰度
 * - 箱式模糊改用滚动求和，复杂度从 O(w·h·r) 降为 O(w·h)
 * - 灰度 / 拉普拉斯 / 模糊 / 合并四段都按行分块多线程并行
 */
class FocusStacker(private val rotateDegrees: Int) {

    companion object {
        private const val WORK_SAMPLE_SIZE = 2    // 解码降采样倍数
        private const val SMOOTH_RADIUS = 3       // 清晰度分数平滑半径，减少接缝
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
    private var best = FloatArray(0)
    private var result = IntArray(0)

    /** 喂入一张照片的 JPEG；解码失败或尺寸不一致的直接丢弃 */
    fun addImage(bytes: ByteArray) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = WORK_SAMPLE_SIZE
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return

        if (width == 0) {
            width = bmp.width
            height = bmp.height
            val n = width * height
            pixels = IntArray(n)
            gray = ByteArray(n)
            score = FloatArray(n)
            tmp = FloatArray(n)
            best = FloatArray(n) { -1f }
            result = IntArray(n)
        } else if (bmp.width != width || bmp.height != height) {
            bmp.recycle()
            return
        }

        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        imageCount++
        mergeOneImage()
    }

    /** 所有照片处理完毕，输出合成结果 */
    fun finish(): Bitmap {
        if (imageCount == 0) throw IllegalStateException("照片解码失败")
        val stacked = Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
        if (rotateDegrees % 360 == 0) return stacked
        val matrix = Matrix().apply { postRotate(rotateDegrees.toFloat()) }
        return Bitmap.createBitmap(stacked, 0, 0, width, height, matrix, true)
    }

    /** 对刚解码完的一张图：计算清晰度并并入结果 */
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

        // 4. 逐像素保留最清晰的一张
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val p = row + x
                    if (score[p] > best[p]) {
                        best[p] = score[p]
                        result[p] = pixels[p]
                    }
                }
            }
        }
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
