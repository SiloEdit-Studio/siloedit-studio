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

    fun unloadAll() {
        sessions.forEach { it.value.close() }
        sessions.clear()
        AiUpscaler.logToFile(context, "ENGINE: Todos los modelos liberados.")
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
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val floatBuffer = FloatBuffer.allocate(3 * w * h)

            // Procesamiento lineal ultra-rápido con bit-shifting
            for (i in 0 until w * h) {
                val p = pixels[i]
                val r: Float
                val g: Float
                val b: Float
                
                if (normType == NormType.MINUS_ONE_TO_ONE) {
                    r = (((p shr 16) and 0xFF) / 127.5f) - 1.0f
                    g = (((p shr 8) and 0xFF) / 127.5f) - 1.0f
                    b = ((p and 0xFF) / 127.5f) - 1.0f
                } else {
                    r = ((p shr 16) and 0xFF) / 255f
                    g = ((p shr 8) and 0xFF) / 255f
                    b = ((p and 0xFF) / 255f) / 1.0f
                }
                
                // Formato NCHW (R, G, B)
                floatBuffer.put(i, r)
                floatBuffer.put(w * h + i, g)
                floatBuffer.put(2 * w * h + i, b)
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
            val outPixels = IntArray(outW * outH)

            for (i in 0 until outW * outH) {
                var r = outputData.get(i)
                var g = outputData.get(outW * outH + i)
                var b = outputData.get(2 * outW * outH + i)

                if (normType == NormType.MINUS_ONE_TO_ONE) {
                    r = ((r + 1.0f) / 2.0f).coerceIn(0f, 1f)
                    g = ((g + 1.0f) / 2.0f).coerceIn(0f, 1f)
                    b = ((b + 1.0f) / 2.0f).coerceIn(0f, 1f)
                } else {
                    r = r.coerceIn(0f, 1f)
                    g = g.coerceIn(0f, 1f)
                    b = b.coerceIn(0f, 1f)
                }
                
                outPixels[i] = (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
            }
            
            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
            
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
