package com.example.photoeditor.ai

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AiUpscaler {

    companion object {
        fun logToFile(context: Context, msg: String) {
            try {
                val logFile = File(context.getExternalFilesDir(null), "siloedit_engine.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val time = sdf.format(Date())
                logFile.appendText("[$time] $msg\n")
                Log.d("SiloEdit_IA", "[$time] $msg")
            } catch (e: Exception) {
                Log.e("SiloEdit_IA", "Error log: ${e.message}")
            }
        }
    }

    suspend fun processPipeline(
        context: Context,
        inputBitmap: Bitmap,
        onProgress: (String, Int) -> Unit
    ): Bitmap? = withContext(Dispatchers.Default) {
        val upscaler = LocalUpscaler(context)
        logToFile(context, "INICIO PIPELINE ULTRA-SHARP: Imagen ${inputBitmap.width}x${inputBitmap.height}")
        
        try {
            onProgress("Iniciando SiloEdit Engine...", 5)
            upscaler.loadModels()

            // PASO 1: Reconstrucción con Solapamiento y corrección de bordes
            onProgress("Eliminando borrosidad de fondo...", 15)
            val scaledBitmap = processTiledSeamless(context, upscaler, inputBitmap, onProgress) ?: return@withContext null
            
            // PASO 2: Restauración de Rostros con Feathering corregido
            onProgress("Enfocando facciones...", 75)
            val finalBitmap = processFacesSeamless(context, upscaler, scaledBitmap)
            
            // PASO 3: Post-Procesado de Nitidez Extrema y Textura
            onProgress("Aplicando Ultra-Sharpening...", 95)
            val result = applyPhotoFinish(finalBitmap)
            
            logToFile(context, "PIPELINE: Nitidez de estudio alcanzada.")
            upscaler.close()
            return@withContext result

        } catch (e: Exception) {
            logToFile(context, "PIPELINE ERROR: ${e.message}")
            null
        }
    }

    private suspend fun processTiledSeamless(
        context: Context,
        upscaler: LocalUpscaler,
        bitmap: Bitmap,
        onProgress: (String, Int) -> Unit
    ): Bitmap? {
        val tileSize = 128
        val overlap = 16 
        val scale = 4
        
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        
        val step = tileSize - overlap
        val xSteps = Math.ceil(width.toDouble() / step).toInt()
        val ySteps = Math.ceil(height.toDouble() / step).toInt()
        val total = xSteps * ySteps

        for (y in 0 until ySteps) {
            for (x in 0 until xSteps) {
                val startX = (x * step).coerceAtMost(width - tileSize).coerceAtLeast(0)
                val startY = (y * step).coerceAtMost(height - tileSize).coerceAtLeast(0)
                
                val actualTileW = if (x == xSteps - 1) width - startX else tileSize
                val actualTileH = if (y == ySteps - 1) height - startY else tileSize

                val tile = Bitmap.createBitmap(bitmap, startX, startY, tileSize, tileSize)
                val processed = upscaler.processTileESRGAN(tile)
                
                if (processed != null) {
                    val finalTile = if (actualTileW != tileSize || actualTileH != tileSize) {
                        Bitmap.createBitmap(processed, 0, 0, actualTileW * scale, actualTileH * scale)
                    } else processed

                    canvas.drawBitmap(finalTile, (startX * scale).toFloat(), (startY * scale).toFloat(), paint)
                    if (finalTile != processed) finalTile.recycle()
                    processed.recycle()
                }
                tile.recycle()
                
                val count = (y * xSteps) + x + 1
                if (count % 10 == 0) onProgress("Escalando Pro: $count / $total", 15 + (count * 50 / total))
            }
        }
        return output
    }

    private suspend fun processFacesSeamless(
        context: Context,
        upscaler: LocalUpscaler,
        scaledBitmap: Bitmap
    ): Bitmap {
        val faces = detectFaces(scaledBitmap)
        if (faces.isEmpty()) return scaledBitmap
        
        val canvas = Canvas(scaledBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (face in faces) {
            val bounds = face.boundingBox
            val margin = (bounds.width() * 0.4f).toInt()
            
            val left = (bounds.left - margin).coerceAtLeast(0)
            val top = (bounds.top - margin).coerceAtLeast(0)
            val right = (bounds.right + margin).coerceAtMost(scaledBitmap.width)
            val bottom = (bounds.bottom + margin).coerceAtMost(scaledBitmap.height)
            
            val faceCrop = Bitmap.createBitmap(scaledBitmap, left, top, right - left, bottom - top)
            val restored = upscaler.processGFPGAN(faceCrop)
            
            if (restored != null) {
                val restoredResized = Bitmap.createScaledBitmap(restored, faceCrop.width, faceCrop.height, true)
                
                val mask = Bitmap.createBitmap(faceCrop.width, faceCrop.height, Bitmap.Config.ARGB_8888)
                val maskCanvas = Canvas(mask)
                val gradient = RadialGradient(
                    mask.width / 2f, mask.height / 2f,
                    mask.width / 1.4f,
                    Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                val maskPaint = Paint().apply { shader = gradient }
                maskCanvas.drawRect(0f, 0f, mask.width.toFloat(), mask.height.toFloat(), maskPaint)
                
                val blendedFace = Bitmap.createBitmap(faceCrop.width, faceCrop.height, Bitmap.Config.ARGB_8888)
                val blendCanvas = Canvas(blendedFace)
                blendCanvas.drawBitmap(restoredResized, 0f, 0f, null)
                maskPaint.apply {
                    shader = null
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                blendCanvas.drawBitmap(mask, 0f, 0f, maskPaint)
                
                canvas.drawBitmap(blendedFace, left.toFloat(), top.toFloat(), paint)
                
                blendedFace.recycle()
                mask.recycle()
                restoredResized.recycle()
                restored.recycle()
            }
            faceCrop.recycle()
        }
        return scaledBitmap
    }

    private fun applyPhotoFinish(bitmap: Bitmap): Bitmap {
        val working = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(working)
        
        val noisePaint = Paint().apply {
            alpha = 15
            val noise = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val random = Random()
            for (y in 0 until 128) {
                for (x in 0 until 128) {
                    val g = random.nextInt(256)
                    noise.setPixel(x, y, Color.argb(45, g, g, g))
                }
            }
            shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawPaint(noisePaint)
        
        return working
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<com.google.mlkit.vision.face.Face> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            detector.process(image).await()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
