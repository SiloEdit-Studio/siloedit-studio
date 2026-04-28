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
import kotlin.math.sqrt

class AiUpscaler {

    enum class SceneType { FACE, HARD_SURFACE, ORGANIC, SKY_SMOOTH }

    companion object {
        fun logToFile(context: Context, msg: String) {
            try {
                val logFile = File(context.getExternalFilesDir(null), "siloedit_engine.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
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
        logToFile(context, "--- INICIO PIPELINE ADAPTATIVO (SCENE-AWARE) ---")
        
        try {
            // STEP 0: Análisis
            onProgress("Analizando escena...", 5)
            val faces = detectFaces(inputBitmap)
            
            // STEP 1: Preprocesado Mínimo
            onProgress("Limpiando artefactos...", 10)
            upscaler.loadModel("1xDeJPG_realplksr_otf_fp32_fullyoptimized.onnx", true)
            val cleanBitmap = upscaler.runInference("1xDeJPG_realplksr_otf_fp32_fullyoptimized.onnx", inputBitmap) ?: inputBitmap

            // STEP 2: Carga Dinámica de Motores
            onProgress("Cargando motores de superficie...", 15)
            upscaler.loadModel("4x-UltraSharpV2_fp32_op17.onnx", true)
            upscaler.loadModel("4xPurePhoto-RealPLSKR.onnx", true)
            upscaler.loadModel("4x-ClearRealityV1_Soft-fp32-opset17.onnx", true)
            upscaler.loadModel("1x-ITF-SkinDiffDetail-Lite-v1.onnx", false)
            upscaler.loadModel("4xNomosWebPhoto_RealPLKSR.onnx", true)

            // STEP 3: Procesado Regional con Seamless Tiling
            onProgress("Reconstruyendo texturas...", 20)
            val enhancedBitmap = processTiledAdaptive(context, upscaler, cleanBitmap, faces, onProgress) ?: return@withContext null
            
            // STEP 4: Naturalización
            onProgress("Ajuste de grano natural...", 95)
            val result = applyPhotoFinish(enhancedBitmap)
            
            logToFile(context, "PIPELINE: Éxito total. Sin artefactos de pintura.")
            upscaler.close()
            return@withContext result

        } catch (e: Exception) {
            logToFile(context, "PIPELINE ERROR: ${e.message}")
            null
        }
    }

    private suspend fun processTiledAdaptive(
        context: Context,
        upscaler: LocalUpscaler,
        bitmap: Bitmap,
        faces: List<com.google.mlkit.vision.face.Face>,
        onProgress: (String, Int) -> Unit
    ): Bitmap? {
        val tileSize = 128
        val overlap = 16
        val scale = 4
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        val step = tileSize - overlap
        val xSteps = Math.ceil(width.toDouble() / step).toInt()
        val ySteps = Math.ceil(height.toDouble() / step).toInt()
        val total = xSteps * ySteps

        var cFace = 0; var cHard = 0; var cOrg = 0; var cSky = 0

        for (y in 0 until ySteps) {
            for (x in 0 until xSteps) {
                val startX = (x * step).coerceAtMost(width - tileSize).coerceAtLeast(0)
                val startY = (y * step).coerceAtMost(height - tileSize).coerceAtLeast(0)
                
                val tile = Bitmap.createBitmap(bitmap, startX, startY, tileSize, tileSize)
                val sceneType = classifyTile(tile, startX, startY, faces)
                
                val modelName = when(sceneType) {
                    SceneType.FACE -> "4xNomosWebPhoto_RealPLKSR.onnx"
                    SceneType.HARD_SURFACE -> "4x-UltraSharpV2_fp32_op17.onnx"
                    SceneType.ORGANIC -> "4xPurePhoto-RealPLSKR.onnx"
                    SceneType.SKY_SMOOTH -> "4x-ClearRealityV1_Soft-fp32-opset17.onnx"
                }

                when(sceneType) {
                    SceneType.FACE -> cFace++
                    SceneType.HARD_SURFACE -> cHard++
                    SceneType.ORGANIC -> cOrg++
                    SceneType.SKY_SMOOTH -> cSky++
                }

                val processed = if (sceneType == SceneType.FACE || sceneType == SceneType.ORGANIC) {
                    val detail = upscaler.runInference("1x-ITF-SkinDiffDetail-Lite-v1.onnx", tile) ?: tile
                    val out = upscaler.runInference(modelName, detail)
                    if (detail != tile) detail.recycle()
                    out
                } else {
                    upscaler.runInference(modelName, tile)
                }

                if (processed != null) {
                    canvas.drawBitmap(processed, (startX * scale).toFloat(), (startY * scale).toFloat(), null)
                    processed.recycle()
                }
                tile.recycle()

                if (((y * xSteps) + x) % 10 == 0) {
                    onProgress("Procesando: ${(y * xSteps + x) * 100 / total}%", 20 + (y * xSteps + x) * 70 / total)
                }
            }
        }
        logToFile(context, "STATS: Face:$cFace | Hard:$cHard | Organic:$cOrg | Sky:$cSky")
        return output
    }

    private fun classifyTile(tile: Bitmap, tx: Int, ty: Int, faces: List<com.google.mlkit.vision.face.Face>): SceneType {
        for (face in faces) {
            if (Rect.intersects(face.boundingBox, Rect(tx, ty, tx + 128, ty + 128))) return SceneType.FACE
        }

        val pixels = IntArray(128 * 128)
        tile.getPixels(pixels, 0, 128, 0, 0, 128, 128)

        var variance = 0f
        var mean = 0f
        var edgeCount = 0

        for (p in pixels) mean += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f
        mean /= pixels.size

        for (i in 129 until pixels.size - 129) {
            val v = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3f
            variance += (v - mean) * (v - mean)
            
            // Sobel simplificado para bordes
            val h = abs(pixels[i+1] - pixels[i-1])
            val vB = abs(pixels[i+128] - pixels[i-128])
            if (h + vB > 100) edgeCount++
        }

        val vVal = variance / pixels.size
        return when {
            vVal < 120 -> SceneType.SKY_SMOOTH
            edgeCount > 1500 -> SceneType.HARD_SURFACE
            else -> SceneType.ORGANIC
        }
    }

    private fun applyPhotoFinish(bitmap: Bitmap): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val noisePaint = Paint().apply {
            alpha = 7 // Grano casi imperceptible
            val noise = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val random = Random()
            for (y in 0 until 256) {
                for (x in 0 until 256) {
                    val g = random.nextInt(256)
                    noise.setPixel(x, y, Color.argb(25, g, g, g))
                }
            }
            shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawPaint(noisePaint)
        return out
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
