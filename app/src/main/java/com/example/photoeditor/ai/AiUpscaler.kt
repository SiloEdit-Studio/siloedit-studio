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

    /**
     * @param targetScale Escala final deseada (2 o 4)
     * @param isEnhanceEnabled Si es true, aplica limpieza de desenfoque reconstruyendo bordes
     */
    suspend fun upscale(context: Context, bitmap: Bitmap, targetScale: Int = 2, isEnhanceEnabled: Boolean = false): Bitmap? = withContext(Dispatchers.Default) {
        logToFile(context, "--- PROCESO IA INICIADO (Modo Limpieza: $isEnhanceEnabled) ---")
        try {
            val localUpscaler = LocalUpscaler(context)
            
            // ESTRATEGIA DE PROCESAMIENTO
            // Para mejorar la calidad (quitar borrosidad), procesamos a una resolución donde la IA es óptima
            val maxInputSize = if (isEnhanceEnabled) 1000f else 1600f
            
            val currentMax = Math.max(bitmap.width, bitmap.height).toFloat()
            val workingBitmap = if (currentMax > maxInputSize) {
                logToFile(context, "Redimensionando para procesamiento IA óptimo...")
                val scale = maxInputSize / currentMax
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }

            // 2. EJECUTAR IA
            logToFile(context, "Ejecutando Red Neuronal sobre ${workingBitmap.width}x${workingBitmap.height}...")
            
            // Este es el paso pesado que reconstruye la imagen
            val aiResult = localUpscaler.upscale(workingBitmap)
            localUpscaler.close()

            if (aiResult == null) {
                logToFile(context, "ERROR: El motor de IA no pudo procesar la imagen.")
                return@withContext null
            }

            // ESCALADO FINAL AL TAMAÑO SOLICITADO (2X o 4X)
            val finalWidth = bitmap.width * targetScale
            val finalHeight = bitmap.height * targetScale
            
            logToFile(context, "Generando imagen final de alta fidelidad (${finalWidth}x${finalHeight})...")
            
            // Usamos un escalado de alta calidad para el paso final
            val finalBitmap = Bitmap.createScaledBitmap(aiResult, finalWidth, finalHeight, true)
            
            logToFile(context, "¡ÉXITO TOTAL!")
            return@withContext finalBitmap

        } catch (e: Exception) {
            logToFile(context, "ERROR CRÍTICO: ${e.localizedMessage}")
            null
        }
    }
}
