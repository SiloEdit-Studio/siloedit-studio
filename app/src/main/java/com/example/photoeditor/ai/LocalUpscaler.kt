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
    private var esrganSession: OrtSession? = null
    private var gfpganSession: OrtSession? = null

    private fun copyAssetToCache(assetName: String, targetName: String): String {
        val outFile = File(context.cacheDir, targetName)
        if (outFile.exists() && outFile.length() > 0) {
            return outFile.absolutePath
        }
        AiUpscaler.logToFile(context, "ENGINE: Copiando Asset $assetName -> $targetName")
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }

    suspend fun loadModels() = withContext(Dispatchers.IO) {
        try {
            if (esrganSession == null) {
                val opts = OrtSession.SessionOptions().apply { addNnapi() }
                val onnxPath = copyAssetToCache("Real-ESRGAN-General-x4v3.onnx", "model.onnx")
                copyAssetToCache("Real-ESRGAN-General-x4v3.data", "model.data")
                esrganSession = ortEnv.createSession(onnxPath, opts)
            }
            if (gfpganSession == null) {
                // GFPGAN es muy pesado (324MB). NNAPI suele fallar. Usamos CPU (Sin addNnapi)
                val opts = OrtSession.SessionOptions()
                val gfpganPath = copyAssetToCache("GFPGANv1.4.onnx", "gfpgan.onnx")
                gfpganSession = ortEnv.createSession(gfpganPath, opts)
                AiUpscaler.logToFile(context, "ENGINE: GFPGAN cargado en modo CPU (Estable)")
            }
        } catch (e: Exception) {
            AiUpscaler.logToFile(context, "ENGINE LOAD ERROR: ${e.message}")
        }
    }

    suspend fun processTileESRGAN(tile: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val session = esrganSession ?: return@withContext null
        val inputTile = if (tile.width != 128 || tile.height != 128) {
            Bitmap.createScaledBitmap(tile, 128, 128, true)
        } else tile
        val result = runInference(session, inputTile, isGFPGAN = false)
        if (inputTile != tile) inputTile.recycle()
        return@withContext result
    }

    suspend fun processGFPGAN(face: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val session = gfpganSession ?: return@withContext null
        val inputFace = Bitmap.createScaledBitmap(face, 512, 512, true)
        AiUpscaler.logToFile(context, "GFPGAN: Iniciando inferencia en rostro...")
        val result = runInference(session, inputFace, isGFPGAN = true)
        AiUpscaler.logToFile(context, "GFPGAN: Inferencia completada.")
        inputFace.recycle()
        return@withContext result
    }

    private fun runInference(session: OrtSession, bitmap: Bitmap, isGFPGAN: Boolean): Bitmap? {
        val inputName = session.inputNames.first()
        try {
            val w = bitmap.width
            val h = bitmap.height
            val floatBuffer = FloatBuffer.allocate(3 * w * h)
            
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = bitmap.getPixel(x, y)
                    if (isGFPGAN) {
                        floatBuffer.put(0 * w * h + y * w + x, (Color.red(p) / 127.5f) - 1.0f)
                        floatBuffer.put(1 * w * h + y * w + x, (Color.green(p) / 127.5f) - 1.0f)
                        floatBuffer.put(2 * w * h + y * w + x, (Color.blue(p) / 127.5f) - 1.0f)
                    } else {
                        floatBuffer.put(0 * w * h + y * w + x, Color.red(p) / 255f)
                        floatBuffer.put(1 * w * h + y * w + x, Color.green(p) / 255f)
                        floatBuffer.put(2 * w * h + y * w + x, Color.blue(p) / 255f)
                    }
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
                    
                    if (isGFPGAN) {
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
            inputTensor.close()
            results.close()
            return outBitmap
        } catch (e: Exception) {
            AiUpscaler.logToFile(context, "INFERENCE ERROR: ${e.message}")
            return null
        }
    }

    fun close() {
        esrganSession?.close()
        gfpganSession?.close()
        ortEnv.close()
    }
}
