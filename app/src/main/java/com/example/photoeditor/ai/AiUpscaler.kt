package com.example.photoeditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AiUpscaler {

    companion object {
        fun logToFile(context: Context, msg: String) {
            try {
                val logFile = File(context.getExternalFilesDir(null), "ai_studio_debug.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                logFile.appendText("[${sdf.format(Date())}] $msg\n")
                Log.d("AI_DEBUG", msg)
            } catch (e: Exception) {
                Log.e("AI_DEBUG", "Error log: ${e.message}")
            }
        }
    }

    suspend fun upscale(context: Context, bitmap: Bitmap, targetScale: Int = 2, isEnhanceEnabled: Boolean = false): Bitmap? = withContext(Dispatchers.Default) {
        logToFile(context, "--- PROCESO IA INICIADO ---")
        logToFile(context, "INFO IMAGEN: ${bitmap.width}x${bitmap.height} px, Config: ${bitmap.config}")
        
        try {
            val localUpscaler = LocalUpscaler(context)
            
            // 1. AJUSTAR DIMENSIONES PARA EL MODELO (DEBEN SER PARES)
            var safeWidth = bitmap.width
            var safeHeight = bitmap.height
            
            // Si son impares, ajustamos para evitar el error de Reshape en ONNX
            if (safeWidth % 2 != 0) safeWidth--
            if (safeHeight % 2 != 0) safeHeight--
            
            val maxInputSize = if (isEnhanceEnabled) 1000f else 1600f
            val currentMax = Math.max(safeWidth, safeHeight).toFloat()
            
            val workingBitmap = if (currentMax > maxInputSize || safeWidth != bitmap.width || safeHeight != bitmap.height) {
                val scale = if (currentMax > maxInputSize) maxInputSize / currentMax else 1f
                val finalW = (safeWidth * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
                val finalH = (safeHeight * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
                
                logToFile(context, "IA -> Ajustando imagen a dimensiones seguras (Pares): ${finalW}x${finalH}")
                Bitmap.createScaledBitmap(bitmap, finalW, finalH, true)
            } else {
                bitmap
            }

            logToFile(context, "IA -> Llamando al motor de inferencia local con imagen de ${workingBitmap.width}x${workingBitmap.height}...")
            val aiResult = localUpscaler.upscale(workingBitmap)
            localUpscaler.close()

            if (aiResult == null) {
                logToFile(context, "ERROR CRÍTICO: El motor de IA no pudo procesar esta imagen específica.")
                return@withContext null
            }

            val finalWidth = bitmap.width * targetScale
            val finalHeight = bitmap.height * targetScale
            
            logToFile(context, "IA -> Reconstruyendo imagen final a ${finalWidth}x${finalHeight}")
            val finalBitmap = Bitmap.createScaledBitmap(aiResult, finalWidth, finalHeight, true)
            
            logToFile(context, "--- ¡EXPORTACIÓN IA COMPLETADA CON ÉXITO! ---")
            return@withContext finalBitmap

        } catch (e: Exception) {
            logToFile(context, "FALLO TOTAL EN PROCESO IA: ${e.localizedMessage}")
            null
        }
    }
}
