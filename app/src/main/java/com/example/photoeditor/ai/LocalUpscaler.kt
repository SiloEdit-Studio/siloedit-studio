package com.example.photoeditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

class LocalUpscaler(private val context: Context) {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()

    private fun copyAssetToCache(assetName: String): String {
        val outFile = File(context.cacheDir, assetName)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        
        AiUpscaler.logToFile(context, "ENGINE: Extrayendo $assetName a caché...")
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }

    suspend fun loadModel(modelName: String, useNnapi: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            if (sessions.containsKey(modelName)) return@withContext true
            
            val opts = OrtSession.SessionOptions()
            if (useNnapi) opts.addNnapi()
            
            val path = copyAssetToCache(modelName)
            val session = ortEnv.createSession(path, opts)
            sessions[modelName] = session
            AiUpscaler.logToFile(context, "ENGINE: [SUCCESS] Modelo $modelName cargado.")
            true
        } catch (e: Exception) {
            AiUpscaler.logToFile(context, "ENGINE: [ERROR] Fallo al cargar $modelName: ${e.message}")
            false
        }
    }

    suspend fun runInference(
        modelName: String, 
        bitmap: Bitmap, 
        normType: NormType = NormType.ZERO_TO_ONE
    ): Bitmap? = withContext(Dispatchers.Default) {
        val session = sessions[modelName] ?: return@withContext null
        val inputName = session.inputNames.first()
        
        try {
            val w = bitmap.width
            val h = bitmap.height
            val floatBuffer = FloatBuffer.allocate(3 * w * h)
            
            var minVal = Float.MAX_VALUE
            var maxVal = Float.MIN_VALUE

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = bitmap.getPixel(x, y)
                    val r: Float
                    val g: Float
                    val b: Float
                    
                    if (normType == NormType.MINUS_ONE_TO_ONE) {
                        r = (Color.red(p) / 127.5f) - 1.0f
                        g = (Color.green(p) / 127.5f) - 1.0f
                        b = (Color.blue(p) / 127.5f) - 1.0f
                    } else {
                        r = Color.red(p) / 255f
                        g = Color.green(p) / 255f
                        b = Color.blue(p) / 255f
                    }
                    
                    floatBuffer.put(0 * w * h + y * w + x, r)
                    floatBuffer.put(1 * w * h + y * w + x, g)
                    floatBuffer.put(2 * w * h + y * w + x, b)
                    
                    if (r < minVal) minVal = r
                    if (r > maxVal) maxVal = r
                }
            }
            floatBuffer.rewind()
            
            val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)
            
            val results = session.run(mapOf(inputName to inputTensor))
            val outputTensor = results.first().value as OnnxTensor
            val outputData = outputTensor.floatBuffer
            val outShape = outputTensor.info.shape
            
            val outH = outShape[2].toInt()
            val outW = outShape[3].toInt()
            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    var r = outputData.get(0 * outW * outH + y * outW + x)
                    var g = outputData.get(1 * outW * outH + y * outW + x)
                    var b = outputData.get(2 * outW * outH + y * outW + x)
                    
                    if (normType == NormType.MINUS_ONE_TO_ONE) {
                        r = ((r + 1.0f) / 2.0f).coerceIn(0f, 1f)
                        g = ((g + 1.0f) / 2.0f).coerceIn(0f, 1f)
                        b = ((b + 1.0f) / 2.0f).coerceIn(0f, 1f)
                    } else {
                        r = r.coerceIn(0f, 1f)
                        g = g.coerceIn(0f, 1f)
                        b = b.coerceIn(0f, 1f)
                    }
                    outBitmap.setPixel(x, y, Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()))
                }
            }
            
            AiUpscaler.logToFile(context, "INFERENCIA: $modelName | InRange: [$minVal, $maxVal] | OutSize: ${outW}x${outH}")
            
            inputTensor.close()
            results.close()
            return@withContext outBitmap
        } catch (e: Exception) {
            AiUpscaler.logToFile(context, "INFERENCIA ERROR: ${e.message}")
            return@withContext null
        }
    }

    enum class NormType { ZERO_TO_ONE, MINUS_ONE_TO_ONE }

    fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        ortEnv.close()
    }
}
