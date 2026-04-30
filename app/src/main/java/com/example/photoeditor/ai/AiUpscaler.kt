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
import kotlin.math.abs

class AiUpscaler {

    enum class SceneType { FACE, HARD_SURFACE, ORGANIC, SKY_SMOOTH }

    companion object {
        fun logToFile(context: Context, msg: String) {
            try {
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val maxMem = runtime.maxMemory() / 1024 / 1024
                val logMsg = "[$usedMem MB / $maxMem MB] $msg"
                
                val logFile = File(context.getExternalFilesDir(null), "siloedit_engine.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val time = sdf.format(Date())
                logFile.appendText("[$time] $logMsg\n")
                Log.d("SiloEdit_IA", "[$time] $logMsg")
            } catch (e: Exception) {
                Log.e("SiloEdit_IA", "Error log: ${e.message}")
            }
        }
    }

    suspend fun processPipeline(
        context: Context,
        inputBitmap: Bitmap,
        isAiEnhance: Boolean,
        isCleanup: Boolean,
        targetScale: Int,
        onProgress: (String, Int) -> Unit
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isAiEnhance && !isCleanup && targetScale <= 1) return@withContext inputBitmap

        val originalWidth = inputBitmap.width
        val originalHeight = inputBitmap.height
        var finalScale = targetScale

        // Seguridad RAM (Safe Scaling)
        if (originalWidth * originalHeight > 4_000_000 && targetScale > 2) {
            finalScale = 2
            logToFile(context, "SAFETY: Imagen grande detected. Escala ajustada a 2x para evitar OOM.")
        }
        if (originalWidth * originalHeight > 15_000_000 && finalScale > 1) {
            finalScale = 1
            logToFile(context, "SAFETY: Imagen masiva. Escalado desactivado para preservar estabilidad.")
        }

        val upscaler = LocalUpscaler(context)
        logToFile(context, "--- INICIO PIPELINE ADAPTATIVO (Scale: $finalScale) ---")

        try {
            var currentBitmap = inputBitmap

            // STEP 1: Limpieza Inteligente (Denoise/DeJPEG)
            if (isCleanup) {
                val cleaned = runSmartCleanup(context, upscaler, currentBitmap, onProgress)
                if (cleaned != null && cleaned != currentBitmap) {
                    // Solo reciclamos si NO es el original y NO es el mismo
                    if (currentBitmap != inputBitmap) currentBitmap.recycle()
                    currentBitmap = cleaned
                }
                upscaler.unloadAll()
                System.gc()
            }

            // STEP 2: Escalado Final e IA Adaptativa
            val targetW = originalWidth * finalScale
            val targetH = originalHeight * finalScale

            if (currentBitmap.width < targetW || isAiEnhance) {
                onProgress("Mejorando texturas...", 40)
                val faces = detectFaces(currentBitmap)

                val upscaled = processTiledAdaptive(upscaler, currentBitmap, faces, finalScale, isAiEnhance, onProgress)
                
                // --- FIX CRASH: Solo reciclamos si no es la misma instancia ---
                if (currentBitmap != inputBitmap && currentBitmap != upscaled) {
                    currentBitmap.recycle()
                }
                currentBitmap = upscaled
            }

            if (currentBitmap.width != targetW || currentBitmap.height != targetH) {
                val finalBitmap = Bitmap.createScaledBitmap(currentBitmap, targetW, targetH, true)
                if (currentBitmap != inputBitmap && currentBitmap != finalBitmap) {
                    currentBitmap.recycle()
                }
                currentBitmap = finalBitmap
            }

            // STEP 3: Naturalización
            onProgress("Ajuste de grano natural...", 95)
            applyPhotoFinishInPlace(context, currentBitmap)

            logToFile(context, "PIPELINE: Completado con éxito. Resultado: ${currentBitmap.width}x${currentBitmap.height}")
            upscaler.close()
            return@withContext currentBitmap

        } catch (e: Throwable) {
            val errorMsg = "PIPELINE CRASH: ${e.message}\n${e.stackTrace.take(3).joinToString("\n")}"
            logToFile(context, errorMsg)
            Log.e("SiloEdit_IA", errorMsg)
            upscaler.close()
            null
        }
    }

    private suspend fun runSmartCleanup(
        context: Context,
        upscaler: LocalUpscaler,
        inputBitmap: Bitmap,
        onProgress: (String, Int) -> Unit
    ): Bitmap? {
        val originalW = inputBitmap.width
        val originalH = inputBitmap.height
        logToFile(context, "CLEANUP: Iniciando limpieza inteligente en ${originalW}x${originalH}")
        
        // Estrategia para velocidad y calidad:
        // Usamos SuperScale_Alt que es menos agresivo que el restaurador de JPG
        val cleanupModel = "1x-SuperScale_Alt_RPLKSR_S.onnx"
        val isLarge = originalW * originalH > 2_500_000
        
        return try {
            if (isLarge) {
                logToFile(context, "CLEANUP: Usando SuperScale_Alt para preservar texturas.")
                onProgress("Optimizando para limpieza...", 10)
                val downscaled = Bitmap.createScaledBitmap(inputBitmap, originalW / 2, originalH / 2, true)
                
                onProgress("Eliminando artefactos...", 20)
                upscaler.loadModel(cleanupModel, false)
                val cleaned = processTiled(context, upscaler, downscaled, cleanupModel, 256)
                if (cleaned != downscaled) downscaled.recycle()
                
                onProgress("Restaurando detalles...", 60)
                upscaler.loadModel("2xNomosUni_compact_otf_medium.onnx", true)
                val restored = processTiled(context, upscaler, cleaned, "2xNomosUni_compact_otf_medium.onnx", 256, targetScale = 2)
                
                // Mezclamos un 35% del original para evitar el efecto acuarela y mantener micro-detalle
                val blended = blendBitmaps(inputBitmap, restored, 0.65f)
                restored.recycle()
                if (cleaned != restored) cleaned.recycle()
                
                logToFile(context, "CLEANUP: Finalizado con mezcla de textura original (65% IA).")
                blended
            } else {
                onProgress("Limpiando...", 20)
                upscaler.loadModel(cleanupModel, false)
                val cleaned = processTiled(context, upscaler, inputBitmap, cleanupModel, 256)
                val blended = blendBitmaps(inputBitmap, cleaned, 0.7f)
                cleaned.recycle()
                blended
            }
        } catch (e: Exception) {
            logToFile(context, "CLEANUP ERROR: ${e.message}")
            null
        }
    }

    private fun blendBitmaps(original: Bitmap, processed: Bitmap, amount: Float): Bitmap {
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        
        // Dibujamos el procesado
        canvas.drawBitmap(processed, null, Rect(0, 0, original.width, original.height), paint)
        
        // Dibujamos el original encima con transparencia para recuperar textura
        paint.alpha = ((1f - amount) * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(original, 0f, 0f, paint)
        
        return result
    }

    private suspend fun processTiled(
        context: Context,
        upscaler: LocalUpscaler,
        bitmap: Bitmap,
        modelName: String,
        tileSize: Int,
        targetScale: Int = 1
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width * targetScale, height * targetScale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
<<<<<<< HEAD
        
        // Aumentamos overlap para mayor suavidad
        val overlap = 32
        val step = tileSize - overlap
        
        val xSteps = kotlin.math.ceil((width - overlap).toDouble() / step).toInt()
        val ySteps = kotlin.math.ceil((height - overlap).toDouble() / step).toInt()

        // Creamos una máscara de desvanecimiento para los bordes de los tiles
        val mask = createFeatherMask(tileSize * targetScale, overlap * targetScale)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }
=======
        val overlap = 16
        val step = tileSize - overlap
        
        val xSteps = kotlin.math.ceil(width.toDouble() / step).toInt()
        val ySteps = kotlin.math.ceil(height.toDouble() / step).toInt()
>>>>>>> 842b54e430b6928ad98350ff0607f6fe160e8cb7

        for (y in 0 until ySteps) {
            for (x in 0 until xSteps) {
                try {
                    val startX = (x * step).coerceAtMost(width - tileSize).coerceAtLeast(0)
                    val startY = (y * step).coerceAtMost(height - tileSize).coerceAtLeast(0)
                    
                    val tile = Bitmap.createBitmap(bitmap, startX, startY, tileSize, tileSize)
                    val processed = upscaler.runInference(modelName, tile)
                    
                    val finalTile = processed ?: Bitmap.createScaledBitmap(tile, tileSize * targetScale, tileSize * targetScale, true)
<<<<<<< HEAD
                    
                    // Mezclado suave de bordes
                    if (x == 0 && y == 0) {
                        canvas.drawBitmap(finalTile, (startX * targetScale).toFloat(), (startY * targetScale).toFloat(), null)
                    } else {
                        val blendedTile = applyMaskToBitmap(finalTile, mask)
                        canvas.drawBitmap(blendedTile, (startX * targetScale).toFloat(), (startY * targetScale).toFloat(), null)
                        blendedTile.recycle()
                    }

=======

                    canvas.drawBitmap(finalTile, (startX * targetScale).toFloat(), (startY * targetScale).toFloat(), null)
                    
>>>>>>> 842b54e430b6928ad98350ff0607f6fe160e8cb7
                    if (processed != null) processed.recycle()
                    tile.recycle()
                } catch (e: Exception) {
                    logToFile(context, "TILE ERROR ($modelName): ${e.message}")
                }
            }
        }
<<<<<<< HEAD
        mask.recycle()
=======
>>>>>>> 842b54e430b6928ad98350ff0607f6fe160e8cb7
        return output
    }

    private suspend fun processTiledAdaptive(
        upscaler: LocalUpscaler,
        bitmap: Bitmap,
        faces: List<com.google.mlkit.vision.face.Face>,
        targetScale: Int,
        isAiEnhance: Boolean,
        onProgress: (String, Int) -> Unit
    ): Bitmap {
        val tileSize = 256 
<<<<<<< HEAD
        val overlap = 32 // Aumentado para evitar costuras
=======
        val overlap = 16
>>>>>>> 842b54e430b6928ad98350ff0607f6fe160e8cb7
        val internalScale = 4 
        val width = bitmap.width
        val height = bitmap.height
        
        val finalW = width * targetScale
        val finalH = height * targetScale
        
        val output = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        val step = tileSize - overlap
<<<<<<< HEAD
        val xSteps = kotlin.math.ceil((width - overlap).toDouble() / step).toInt()
        val ySteps = kotlin.math.ceil((height - overlap).toDouble() / step).toInt()
        val total = xSteps * ySteps

        val mask = createFeatherMask(tileSize * targetScale, overlap * targetScale)

=======
        val xSteps = kotlin.math.ceil(width.toDouble() / step).toInt()
        val ySteps = kotlin.math.ceil(height.toDouble() / step).toInt()
        val total = xSteps * ySteps

>>>>>>> 842b54e430b6928ad98350ff0607f6fe160e8cb7
        for (y in 0 until ySteps) {
            for (x in 0 until xSteps) {
                val startX = (x * step).coerceAtMost(width - tileSize).coerceAtLeast(0)
                val startY = (y * step).coerceAtMost(height - tileSize).coerceAtLeast(0)
                
                val tile = Bitmap.createBitmap(bitmap, startX, startY, tileSize, tileSize)
                
                val sceneType = if (isAiEnhance) classifyTile(tile, startX, startY, faces) else SceneType.HARD_SURFACE
                
                val modelName = when(sceneType) {
                    SceneType.FACE -> "4xNomosWebPhoto_RealPLKSR.onnx"
                    SceneType.HARD_SURFACE -> "4x-UltraSharpV2_fp32_op17.onnx"
                    SceneType.ORGANIC -> "4xPurePhoto-RealPLSKR.onnx"
                    SceneType.SKY_SMOOTH -> "4x-ClearRealityV1_Soft-fp32-opset17.onnx"
                }

                upscaler.loadModel(modelName, true)
                
                val processed = if (isAiEnhance && (sceneType == SceneType.FACE || sceneType == SceneType.ORGANIC)) {
                    upscaler.loadModel("1x-ITF-SkinDiffDetail-Lite-v1.onnx", false)
                    val detail = upscaler.runInference("1x-ITF-SkinDiffDetail-Lite-v1.onnx", tile) ?: tile
                    val out = upscaler.runInference(modelName, detail)
                    if (detail != tile) detail.recycle()
                    out
                } else {
                    upscaler.runInference(modelName, tile)
                }

<<<<<<< HEAD
                val finalTile = processed?.let { pTile ->
                    if (targetScale != internalScale) {
                        val scaledTile = Bitmap.createScaledBitmap(pTile, tileSize * targetScale, tileSize * targetScale, true)
                        pTile.recycle()
                        scaledTile
                    } else pTile
                } ?: Bitmap.createScaledBitmap(tile, tileSize * targetScale, tileSize * targetScale, true)

                // Mezclado suave para evitar cuadrados/costuras
                if (x == 0 && y == 0) {
                    canvas.drawBitmap(finalTile, (startX * targetScale).toFloat(), (startY * targetScale).toFloat(), null)
                } else {
                    val blendedTile = applyMaskToBitmap(finalTile, mask)
                    canvas.drawBitmap(blendedTile, (startX * targetScale).toFloat(), (startY * targetScale).toFloat(), null)
                    blendedTile.recycle()
                }
=======
                val finalTile = processed?.let {
                    if (targetScale != internalScale) {
                        Bitmap.createScaledBitmap(it, tileSize * targetScale, tileSize * targetScale, true).also { it.recycle() }
                    } else it
                } ?: Bitmap.createScaledBitmap(tile, tileSize * targetScale, tileSize * targetScale, true)

                canvas.drawBitmap(finalTile, (startX * targetScale).toFloat(), (startY * targetScale).toFloat(), null)
>>>>>>> 842b54e430b6928ad98350ff0607f6fe160e8cb7
                
                if (finalTile != tile) finalTile.recycle()
                tile.recycle()
                
                if (((y * xSteps) + x) % 10 == 0) {
                    onProgress("Procesando: ${(y * xSteps + x) * 100 / total}%", 40 + (y * xSteps + x) * 50 / total)
                }
            }
        }
<<<<<<< HEAD
        mask.recycle()
=======
>>>>>>> 842b54e430b6928ad98350ff0607f6fe160e8cb7
        return output
    }

    private fun createFeatherMask(size: Int, feather: Int): Bitmap {
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        // Llenar con negro (opaco)
        paint.color = Color.BLACK
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        
        // Gradientes en los 4 bordes para desvanecer
        val transparent = Color.TRANSPARENT
        val opaque = Color.BLACK
        
        // Izquierda
        paint.shader = LinearGradient(0f, 0f, feather.toFloat(), 0f, transparent, opaque, Shader.TileMode.CLAMP)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawRect(0f, 0f, feather.toFloat(), size.toFloat(), paint)
        
        // Arriba
        paint.shader = LinearGradient(0f, 0f, 0f, feather.toFloat(), transparent, opaque, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), feather.toFloat(), paint)
        
        return mask
    }

    private fun applyMaskToBitmap(src: Bitmap, mask: Bitmap): Bitmap {
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, 0f, 0f, paint)
        return result
    }

    private fun classifyTile(tile: Bitmap, tx: Int, ty: Int, faces: List<com.google.mlkit.vision.face.Face>): SceneType {
        for (face in faces) {
            if (Rect.intersects(face.boundingBox, Rect(tx, ty, tx + 256, ty + 256))) return SceneType.FACE
        }

        val w = tile.width
        val h = tile.height
        val pixels = IntArray(w * h)
        tile.getPixels(pixels, 0, w, 0, 0, w, h)

        var variance = 0f
        var mean = 0f
        var edgeCount = 0

        for (p in pixels) mean += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f
        mean /= pixels.size

        // Muestreo rápido para clasificación
        for (i in (w + 1) until (pixels.size - w - 1) step 8) {
            val v = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3f
            variance += (v - mean) * (v - mean)
            
            val diff = abs(((pixels[i+1] shr 16) and 0xFF) - ((pixels[i-1] shr 16) and 0xFF))
            if (diff > 40) edgeCount++
        }

        val vVal = variance / (pixels.size / 8f)
        return when {
            vVal < 90 -> SceneType.SKY_SMOOTH
            edgeCount > 300 -> SceneType.HARD_SURFACE
            else -> SceneType.ORGANIC
        }
    }

    private fun applyPhotoFinishInPlace(context: Context, bitmap: Bitmap) {
        if (!bitmap.isMutable) return
        try {
            val canvas = Canvas(bitmap)
            val noisePaint = Paint().apply {
                alpha = 8
                val noise = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                val random = Random()
                for (y in 0 until 128) {
                    for (x in 0 until 128) {
                        val g = random.nextInt(256)
                        noise.setPixel(x, y, Color.argb(25, g, g, g))
                    }
                }
                shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
            canvas.drawPaint(noisePaint)
            logToFile(context, "FINISH: Grano aplicado.")
        } catch (e: Throwable) {
            logToFile(context, "FINISH ERROR: ${e.message}")
        }
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<com.google.mlkit.vision.face.Face> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        val detector = FaceDetection.getClient(options)
        return try {
            detector.process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
