package com.example.photoeditor.ai

import android.content.Context
import android.graphics.*
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

class AiSegmentation(private val context: Context) {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val inputSize = 1024

    suspend fun loadModel(useFp16: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // Ignoramos el flag fp16 y usamos siempre el modelo de alta precisión que funciona mejor
        val modelName = "InSPyReNet.onnx"
        
        try {
            close() 
            
            val modelFile = File(context.cacheDir, modelName)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                AiUpscaler.logToFile(context, "INSPYRENET: Extrayendo modelo de alta precisión...")
                context.assets.open(modelName).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            val opts = OrtSession.SessionOptions()
            session = ortEnv.createSession(modelFile.absolutePath, opts)
            AiUpscaler.logToFile(context, "INSPYRENET: [SUCCESS] Modelo de alta precisión cargado.")
            true
        } catch (e: Exception) {
            AiUpscaler.logToFile(context, "INSPYRENET ERROR: ${e.message}")
            false
        }
    }

    suspend fun getPersonMask(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val sess = session ?: return@withContext null
        
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val pixels = IntArray(inputSize * inputSize)
            resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            
            val floatBuffer = FloatBuffer.allocate(3 * inputSize * inputSize)
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = floatArrayOf(0.229f, 0.224f, 0.225f)

            for (i in 0 until inputSize * inputSize) {
                val p = pixels[i]
                floatBuffer.put(i, (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0])
                floatBuffer.put(inputSize * inputSize + i, (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1])
                floatBuffer.put(2 * inputSize * inputSize + i, ((p and 0xFF) / 255f - mean[2]) / std[2])
            }
            floatBuffer.rewind()
            
            val inputName = sess.inputNames.first()
            val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)
            
            val results = sess.run(mapOf(inputName to inputTensor))
            val outputValue = results.first().value as OnnxTensor
            val outputData = outputValue.floatBuffer
            outputData.rewind()
            
            val dataSize = outputData.remaining()
            val outputArray = FloatArray(dataSize)
            outputData.get(outputArray)
            
            val maskPixels = IntArray(inputSize * inputSize)
            var countHigh = 0
            
            // InSPyReNet High Precision devuelve probabilidades 0..1 directamente
            for (i in 0 until inputSize * inputSize) {
                if (i >= dataSize) break
                val probability = outputArray[i].coerceIn(0f, 1f)
                
                // Umbralización para nitidez absoluta del sujeto
                val alphaVal = if (probability > 0.5f) 255 else 0
                
                if (alphaVal > 200) countHigh++
                maskPixels[i] = (alphaVal shl 24) or 0x00FFFFFF
            }
            
            AiUpscaler.logToFile(context, "INSPYRENET: Sujeto detectado en $countHigh píxeles.")
            
            val maskBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            maskBitmap.setPixels(maskPixels, 0, inputSize, 0, 0, inputSize, inputSize)
            
            inputTensor.close()
            results.close()
            resized.recycle()
            
            val fullMask = Bitmap.createScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)
            maskBitmap.recycle()
            
            return@withContext fullMask
            
        } catch (e: Exception) {
            AiUpscaler.logToFile(context, "INSPYRENET ERROR: ${e.message}")
            return@withContext null
        }
    }

    fun close() {
        session?.close()
        session = null
    }
}
