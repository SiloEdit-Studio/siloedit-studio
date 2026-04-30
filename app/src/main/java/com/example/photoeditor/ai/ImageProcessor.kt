package com.example.photoeditor.ai

import android.content.Context
import android.graphics.*
import androidx.compose.ui.graphics.ColorMatrix
import kotlin.math.max
import kotlin.math.min

class ImageProcessor {

    // =========================
    // 🌫️ DESENFOQUE SELECTIVO (MODO RETRATO)
    // =========================
    fun applySelectiveBlur(
        src: Bitmap,
        mask: Bitmap,
        blurRadius: Float,
        manualMask: Bitmap? = null,
        isNaturalDepth: Boolean = true,
        focusY: Float = 0.8f,
        focusWidth: Float = 0.15f,
        focusGradient: Float = 0.2f
    ): Bitmap {
        if (blurRadius <= 0f) return src

        val w = src.width
        val h = src.height

        // 1. Asegurar que la máscara coincida en tamaño con la fuente
        val scaledMask = if (mask.width != w || mask.height != h) {
            Bitmap.createScaledBitmap(mask, w, h, true)
        } else mask

        // 2. Crear fondo con desenfoque (Natural o Plano)
        val blurredBackground = if (isNaturalDepth) {
            applyDepthBlur(src, blurRadius, focusY, focusWidth, focusGradient)
        } else {
            val blurScale = if (blurRadius > 15) 4 else 2
            betterFastBlur(src, blurRadius.toInt(), blurScale)
        }

        // 3. Preparar el resultado FINAL
        val result = blurredBackground.copy(Bitmap.Config.ARGB_8888, true)
        val pixRes = IntArray(w * h)
        val pixSrc = IntArray(w * h)
        val pixMask = IntArray(w * h)
        val pixManual = if (manualMask != null) IntArray(w * h) else null

        result.getPixels(pixRes, 0, w, 0, 0, w, h)
        src.getPixels(pixSrc, 0, w, 0, 0, w, h)
        scaledMask.getPixels(pixMask, 0, w, 0, 0, w, h)
        manualMask?.let { 
            val scaledManual = if (it.width != w || it.height != h) Bitmap.createScaledBitmap(it, w, h, true) else it
            scaledManual.getPixels(pixManual!!, 0, w, 0, 0, w, h)
            if (scaledManual != it) scaledManual.recycle()
        }

        // 4. Alpha Blending quirúrgico (Mapeo de máscara IA + Manual)
        for (i in 0 until w * h) {
            val aiAlpha = (pixMask[i] shr 24) and 0xFF
            val manAlpha = if (pixManual != null) (pixManual[i] shr 24) and 0xFF else 0
            val finalAlpha = kotlin.math.max(aiAlpha, manAlpha)

            if (finalAlpha > 0) {
                if (finalAlpha == 255) {
                    pixRes[i] = pixSrc[i]
                } else {
                    val ratio = finalAlpha / 255f
                    val invRatio = 1f - ratio
                    val r = ((pixSrc[i] shr 16 and 0xFF) * ratio + (pixRes[i] shr 16 and 0xFF) * invRatio).toInt()
                    val g = ((pixSrc[i] shr 8 and 0xFF) * ratio + (pixRes[i] shr 8 and 0xFF) * invRatio).toInt()
                    val b = ((pixSrc[i] and 0xFF) * ratio + (pixRes[i] and 0xFF) * invRatio).toInt()
                    pixRes[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        result.setPixels(pixRes, 0, w, 0, 0, w, h)
        
        blurredBackground.recycle()
        if (scaledMask != mask) scaledMask.recycle()
        
        return result
    }

    private fun applyDepthBlur(
        src: Bitmap, 
        maxRadius: Float, 
        focusY: Float = 0.8f,
        focusWidth: Float = 0.15f,
        focusGradient: Float = 0.2f
    ): Bitmap {
        val w = src.width
        val h = src.height
        
        // Capas de desenfoque con radios progresivos para suavidad extrema
        val farBlur = betterFastBlur(src, maxRadius.toInt(), 4)
        val midBlur = betterFastBlur(src, (maxRadius * 0.6f).toInt(), 2)
        
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 1. Capa Base: Fondo total desenfocado
        canvas.drawBitmap(farBlur, 0f, 0f, null)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val opaque = 0xFF000000.toInt()
        val transparent = 0x00000000.toInt()

        // 2. Capa Media: Transición suave (midBlur)
        val midLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val midCanvas = Canvas(midLayer)
        midCanvas.drawBitmap(midBlur, 0f, 0f, null)
        
        // El área de transición es más amplia para evitar bordes duros
        val midStart = (focusY - focusWidth - focusGradient * 1.5f).coerceAtLeast(-0.2f)
        val midEnd = (focusY + focusWidth + focusGradient * 1.5f).coerceAtMost(1.2f)
        
        val midGradient = LinearGradient(
            0f, h * midStart, 
            0f, h * midEnd, 
            intArrayOf(transparent, opaque, opaque, transparent),
            floatArrayOf(0f, 0.4f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        maskPaint.shader = midGradient
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        midCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), maskPaint)
        canvas.drawBitmap(midLayer, 0f, 0f, null)

        // 3. Suelo Nítido (Sharp) - Con gradiente progresivo de 5 pasos
        val sharpLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sharpCanvas = Canvas(sharpLayer)
        sharpCanvas.drawBitmap(src, 0f, 0f, null)
        
        val sharpStart = (focusY - focusWidth).coerceAtLeast(-0.1f)
        val sharpEnd = (focusY + focusWidth).coerceAtMost(1.1f)

        val sharpGradient = LinearGradient(
            0f, h * sharpStart, 
            0f, h * sharpEnd, 
            intArrayOf(transparent, transparent, opaque, opaque, transparent, transparent),
            floatArrayOf(0f, 0.15f, 0.4f, 0.6f, 0.85f, 1f),
            Shader.TileMode.CLAMP
        )
        maskPaint.shader = sharpGradient
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        sharpCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), maskPaint)
        canvas.drawBitmap(sharpLayer, 0f, 0f, null)

        farBlur.recycle(); midBlur.recycle(); midLayer.recycle(); sharpLayer.recycle()
        return result
    }

    private fun betterFastBlur(src: Bitmap, radius: Int, scale: Int): Bitmap {
        val w = (src.width / scale).coerceAtLeast(1)
        val h = (src.height / scale).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val blurredSmall = applyStackBlur(scaled, (radius / scale).coerceAtLeast(1))
        return Bitmap.createScaledBitmap(blurredSmall, src.width, src.height, true).also {
            scaled.recycle()
            blurredSmall.recycle()
        }
    }

    // Stack Blur Algorithm with Alpha support
    fun applyStackBlur(src: Bitmap, radius: Int): Bitmap {
        val bitmap = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1; val hm = h - 1; val wh = w * h
        val div = radius + radius + 1
        val r = IntArray(wh); val g = IntArray(wh); val b = IntArray(wh); val a = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int; var asum: Int
        var x: Int; var y: Int; var i: Int; var p: Int; var yp: Int; var yi: Int; var yw: Int
        val vmin = IntArray(max(w, h))

        val divsum = (div + 1 shr 1) * (div + 1 shr 1)
        val dv = IntArray(256 * divsum)
        for (i in 0 until 256 * divsum) dv[i] = i / divsum

        yw = 0; yi = 0
        val stack = Array(div) { IntArray(4) }
        var stackpointer: Int; var stackstart: Int; var sir: IntArray; var rbs: Int
        val r1 = radius + 1
        var routsum: Int; var goutsum: Int; var boutsum: Int; var aoutsum: Int
        var rinsum: Int; var ginsum: Int; var binsum: Int; var ainsum: Int

        for (y in 0 until h) {
            rinsum = 0; ginsum = 0; binsum = 0; ainsum = 0
            routsum = 0; goutsum = 0; boutsum = 0; aoutsum = 0
            rsum = 0; gsum = 0; bsum = 0; asum = 0
            for (i in -radius..radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = p shr 16 and 0xff; sir[1] = p shr 8 and 0xff; sir[2] = p and 0xff; sir[3] = p shr 24 and 0xff
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs; asum += sir[3] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3]
                }
            }
            stackpointer = radius
            for (x in 0 until w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]; a[yi] = dv[asum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum; asum -= aoutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3]
                if (y == 0) vmin[x] = min(x + radius + 1, wm)
                p = pix[yw + vmin[x]]
                sir[0] = p shr 16 and 0xff; sir[1] = p shr 8 and 0xff; sir[2] = p and 0xff; sir[3] = p shr 24 and 0xff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
                rsum += rinsum; gsum += ginsum; bsum += binsum; asum += ainsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]; ainsum -= sir[3]
                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            rinsum = 0; ginsum = 0; binsum = 0; ainsum = 0
            routsum = 0; goutsum = 0; boutsum = 0; aoutsum = 0
            rsum = 0; gsum = 0; bsum = 0; asum = 0
            yp = -radius * w
            for (i in -radius..radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]; sir[3] = a[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs; asum += a[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3]
                }
                if (i < hm) yp += w
            }
            yi = x
            stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (dv[asum] shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum; asum -= aoutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3]
                if (x == 0) vmin[y] = min(y + r1, hm) * w
                p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]; sir[3] = a[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
                rsum += rinsum; gsum += ginsum; bsum += binsum; asum += ainsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]; ainsum -= sir[3]
                yi += w
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    // =========================
    // 🎨 MATRIZ DE COLOR
    // =========================
    fun getLightroomMatrix(
        exposure: Float, contrast: Float, saturation: Float, vibrance: Float, 
        temp: Float, tint: Float, highlights: Float, shadows: Float, 
        blancos: Float, negros: Float
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
        val a = m1.values; val b = m2.values; val result = FloatArray(20)
        for (i in 0 until 4) {
            for (j in 0 until 5) {
                result[i * 5 + j] = a[i * 5 + 0] * b[0 * 5 + j] + a[i * 5 + 1] * b[1 * 5 + j] + a[i * 5 + 2] * b[2 * 5 + j] + a[i * 5 + 3] * b[3 * 5 + j]
                if (j == 4) result[i * 5 + j] += a[i * 5 + 4]
            }
        }
        return ColorMatrix(result)
    }

    // =========================
    // 🚀 PIPELINE PRINCIPAL
    // =========================
    fun applyFinalEffects(
        context: Context, inputBitmap: Bitmap, matrix: ColorMatrix, 
        vignette: Float, sharpenAmount: Float, textureAmount: Float
    ): Bitmap {
        var workingBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (textureAmount > 0) workingBitmap = applyTexture(workingBitmap, textureAmount)
        if (sharpenAmount > 0) workingBitmap = sharpen(workingBitmap, sharpenAmount)
        val result = Bitmap.createBitmap(workingBitmap.width, workingBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(matrix.values))
        }
        canvas.drawBitmap(workingBitmap, 0f, 0f, paint)
        if (vignette != 0f) applyVignette(canvas, result.width, result.height, vignette)
        return result
    }

    fun sharpen(bitmap: Bitmap, amount: Float): Bitmap {
        val a = (amount / 100f).coerceIn(0f, 2f)
        val kernel = floatArrayOf(0f, -a, 0f, -a, 1f + 4f * a, -a, 0f, -a, 0f)
        return applyConvolution(bitmap, kernel)
    }

    private fun applyTexture(bitmap: Bitmap, amount: Float): Bitmap {
        val blurred = betterFastBlur(bitmap, 8, 4)
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

    private fun applyConvolution(src: Bitmap, kernel: FloatArray): Bitmap {
        val width = src.width; val height = src.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height); val newPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var r = 0f; var g = 0f; var b = 0f; var index = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = pixels[(y + ky) * width + (x + kx)]
                        r += Color.red(pixel) * kernel[index]
                        g += Color.green(pixel) * kernel[index]
                        b += Color.blue(pixel) * kernel[index]
                        index++
                    }
                }
                newPixels[y * width + x] = Color.rgb(min(255, max(0, r.toInt())), min(255, max(0, g.toInt())), min(255, max(0, b.toInt())))
            }
        }
        result.setPixels(newPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun applyVignette(canvas: Canvas, w: Int, h: Int, strength: Float) {
        val radius = Math.sqrt((w * w + h * h).toDouble()).toFloat() / 2f
        val gradient = RadialGradient(w / 2f, h / 2f, radius * (1.3f - (Math.abs(strength) / 100f)), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        val paint = Paint().apply {
            shader = gradient
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
            alpha = (Math.abs(strength) * 2.55f).toInt().coerceIn(0, 255)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }
}
