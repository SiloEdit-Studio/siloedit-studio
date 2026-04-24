package com.example.photoeditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

class LocalUpscaler(private val context: Context) {

    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    init {
        try {
            val modelBytes = context.assets.open("model.onnx").readBytes()
            val options = OrtSession.SessionOptions()
            options.addNnapi() 
            ortSession = ortEnv.createSession(modelBytes, options)
        } catch (e: Exception) {
            AiUpscaler.logToFile(context, "Error al iniciar ONNX: ${e.message}")
        }
    }

    suspend fun upscale(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val session = ortSession ?: run {
            AiUpscaler.logToFile(context, "ERROR: Sesión ONNX no inicializada.")
            return@withContext null
        }

        try {
            val width = bitmap.width
            val height = bitmap.height
            AiUpscaler.logToFile(context, "LocalUpscaler -> Recibida imagen de ${width}x${height} para procesar.")
            
            // Detectar formato NCHW/NHWC automáticamente
            val inputInfo = session.inputInfo.values.first().info as TensorInfo
            val shape = inputInfo.shape
            val isNCHW = if (shape.size >= 4) shape[1] == 3L else true
            AiUpscaler.logToFile(context, "LocalUpscaler -> Formato detectado: ${if (isNCHW) "NCHW (Canales Primero)" else "NHWC (Canales Final)"}")
            
            val floatBuffer = FloatBuffer.allocate(3 * width * height)
            
            if (isNCHW) {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val pixel = bitmap.getPixel(x, y)
                        floatBuffer.put(0 * width * height + y * width + x, Color.red(pixel) / 255f)
                        floatBuffer.put(1 * width * height + y * width + x, Color.green(pixel) / 255f)
                        floatBuffer.put(2 * width * height + y * width + x, Color.blue(pixel) / 255f)
                    }
                }
            } else {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val pixel = bitmap.getPixel(x, y)
                        floatBuffer.put(Color.red(pixel) / 255f)
                        floatBuffer.put(Color.green(pixel) / 255f)
                        floatBuffer.put(Color.blue(pixel) / 255f)
                    }
                }
            }

            val inputName = session.inputNames.first()
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, 
                if (isNCHW) longArrayOf(1, 3, height.toLong(), width.toLong()) 
                else longArrayOf(1, height.toLong(), width.toLong(), 3))
            
            AiUpscaler.logToFile(context, "LocalUpscaler -> Ejecutando inferencia ONNX...")
            val results = session.run(mapOf(inputName to inputTensor))
            val outputTensor = results.first().value as OnnxTensor
            
            val outputShape = outputTensor.info.shape
            val outH = if (isNCHW) outputShape[2].toInt() else outputShape[1].toInt()
            val outW = if (isNCHW) outputShape[3].toInt() else outputShape[2].toInt()
            AiUpscaler.logToFile(context, "LocalUpscaler -> Inferencia completada. Salida: ${outW}x${outH}")
            
            val outputData = outputTensor.floatBuffer
            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

            if (isNCHW) {
                for (y in 0 until outH) {
                    for (x in 0 until outW) {
                        val r = (outputData.get(0 * outW * outH + y * outW + x).coerceIn(0f, 1f) * 255).toInt()
                        val g = (outputData.get(1 * outW * outH + y * outW + x).coerceIn(0f, 1f) * 255).toInt()
                        val b = (outputData.get(2 * outW * outH + y * outW + x).coerceIn(0f, 1f) * 255).toInt()
                        outBitmap.setPixel(x, y, Color.rgb(r, g, b))
                    }
                }
            } else {
                outputData.rewind()
                for (y in 0 until outH) {
                    for (x in 0 until outW) {
                        if (outputData.hasRemaining()) {
                            val r = (outputData.get().coerceIn(0f, 1f) * 255).toInt()
                            val g = (outputData.get().coerceIn(0f, 1f) * 255).toInt()
                            val b = (outputData.get().coerceIn(0f, 1f) * 255).toInt()
                            outBitmap.setPixel(x, y, Color.rgb(r, g, b))
                        }
                    }
                }
            }
            
            inputTensor.close()
            results.close()
            AiUpscaler.logToFile(context, "LocalUpscaler -> Bitmap de salida generado correctamente.")
            return@withContext outBitmap

        } catch (e: Exception) {
            AiUpscaler.logToFile(context, "LocalUpscaler -> ERROR en Inferencia: ${e.message}")
            null
        }
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}
