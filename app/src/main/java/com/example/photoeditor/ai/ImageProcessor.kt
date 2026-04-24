package com.example.photoeditor.ai

import android.content.Context
import android.graphics.*
import androidx.compose.ui.graphics.ColorMatrix
import kotlin.math.max
import kotlin.math.min

class ImageProcessor {

    // =========================
    // 🎨 MATRIZ DE COLOR (OK)
    // =========================
    fun getLightroomMatrix(
        exposure: Float,
        contrast: Float,
        saturation: Float,
        vibrance: Float,
        temp: Float,
        tint: Float,
        highlights: Float,
        shadows: Float,
        blancos: Float,
        negros: Float
    ): ColorMatrix {

        val matrix = ColorMatrix()

        val rTemp = 1f + (temp / 200f) + (tint / 400f)
        val gTemp = 1f - (tint / 200f)
        val bTemp = 1f - (temp / 200f) + (tint / 400f)

        val hScale = 1f + (highlights / 600f)
        val sOffset = shadows * 0.3f
        val bScale = 1f + (blancos / 400f)
        val nOffset = negros * 0.4f

        val c = contrast
        val exp = exposure / 100f
        val off = (1f - c) * 0.5f + exp

        val combinedArray = floatArrayOf(
            c * rTemp * hScale * bScale, 0f, 0f, 0f, (off * 255f) + sOffset + nOffset,
            0f, c * gTemp * hScale * bScale, 0f, 0f, (off * 255f) + sOffset + nOffset,
            0f, 0f, c * bTemp * hScale * bScale, 0f, (off * 255f) + sOffset + nOffset,
            0f, 0f, 0f, 1f, 0f
        )

        matrix.set(ColorMatrix(combinedArray))

        val satMatrix = ColorMatrix()
        satMatrix.setToSaturation(saturation * (1f + vibrance * 0.2f))

        return multiplyMatrices(matrix, satMatrix)
    }

    private fun multiplyMatrices(m1: ColorMatrix, m2: ColorMatrix): ColorMatrix {
        val a = m1.values
        val b = m2.values
        val result = FloatArray(20)

        for (i in 0 until 4) {
            for (j in 0 until 5) {
                result[i * 5 + j] =
                    a[i * 5 + 0] * b[0 * 5 + j] +
                            a[i * 5 + 1] * b[1 * 5 + j] +
                            a[i * 5 + 2] * b[2 * 5 + j] +
                            a[i * 5 + 3] * b[3 * 5 + j]

                if (j == 4) result[i * 5 + j] += a[i * 5 + 4]
            }
        }

        return ColorMatrix(result)
    }

    // =========================
    // 🚀 PIPELINE PRINCIPAL
    // =========================
    fun applyFinalEffects(
        context: Context,
        inputBitmap: Bitmap,
        matrix: ColorMatrix,
        vignette: Float,
        sharpenAmount: Float,
        textureAmount: Float
    ): Bitmap {

        var workingBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)

        // 1. TEXTURA (claridad)
        if (textureAmount > 0) {
            workingBitmap = applyTexture(workingBitmap, textureAmount)
        }

        // 2. SHARPEN REAL
        if (sharpenAmount > 0) {
            workingBitmap = sharpen(workingBitmap, sharpenAmount)
        }

        // 3. COLOR FINAL
        val result = Bitmap.createBitmap(
            workingBitmap.width,
            workingBitmap.height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(result)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(matrix.values))
        }

        canvas.drawBitmap(workingBitmap, 0f, 0f, paint)

        // 4. VIÑETA
        if (vignette != 0f) {
            applyVignette(canvas, result.width, result.height, vignette)
        }

        return result
    }

    // =========================
    // 🔍 SHARPEN REAL (CONVOLUCIÓN NORMALIZADA)
    // =========================
    private fun sharpen(bitmap: Bitmap, amount: Float): Bitmap {
        // Normalizamos el amount (0 a 100 -> 0.0 a 1.0)
        val a = (amount / 100f).coerceIn(0f, 2f)
        
        // Kernel de Nitidez que siempre suma 1 (evita que la imagen se "queme")
        val kernel = floatArrayOf(
            0f, -a, 0f,
            -a, 1f + 4f * a, -a,
            0f, -a, 0f
        )

        return applyConvolution(bitmap, kernel)
    }

    // =========================
    // 🧱 TEXTURA (CLARITY)
    // =========================
    private fun applyTexture(bitmap: Bitmap, amount: Float): Bitmap {

        val blurred = fastBlur(bitmap, 8)

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val paint = Paint().apply {
            alpha = (amount * 0.7f).toInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        }

        canvas.drawBitmap(blurred, 0f, 0f, paint)

        return result
    }

    // =========================
    // 🌫️ BLUR SIMPLE (reemplazo RS)
    // =========================
    private fun fastBlur(src: Bitmap, radius: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(
            src,
            src.width / 4,
            src.height / 4,
            true
        )
        val blurred = Bitmap.createScaledBitmap(
            scaled,
            src.width,
            src.height,
            true
        )
        return blurred
    }

    // =========================
    // 🧠 CONVOLUCIÓN BASE
    // =========================
    private fun applyConvolution(src: Bitmap, kernel: FloatArray): Bitmap {

        val width = src.width
        val height = src.height

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        val newPixels = IntArray(width * height)

        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {

                var r = 0f
                var g = 0f
                var b = 0f

                var index = 0

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = pixels[(y + ky) * width + (x + kx)]

                        r += Color.red(pixel) * kernel[index]
                        g += Color.green(pixel) * kernel[index]
                        b += Color.blue(pixel) * kernel[index]

                        index++
                    }
                }

                val nr = min(255, max(0, r.toInt()))
                val ng = min(255, max(0, g.toInt()))
                val nb = min(255, max(0, b.toInt()))

                newPixels[y * width + x] = Color.rgb(nr, ng, nb)
            }
        }

        result.setPixels(newPixels, 0, width, 0, 0, width, height)
        return result
    }

    // =========================
    // 🌑 VIÑETA
    // =========================
    private fun applyVignette(canvas: Canvas, w: Int, h: Int, strength: Float) {

        val radius = Math.sqrt((w * w + h * h).toDouble()).toFloat() / 2f

        val gradient = RadialGradient(
            w / 2f,
            h / 2f,
            radius * (1.3f - (Math.abs(strength) / 100f)),
            Color.TRANSPARENT,
            Color.BLACK,
            Shader.TileMode.CLAMP
        )

        val paint = Paint().apply {
            shader = gradient
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
            alpha = (Math.abs(strength) * 2.55f).toInt().coerceIn(0, 255)
        }

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }
}