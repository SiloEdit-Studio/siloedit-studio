package com.example.photoeditor.ai

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File

class ExportService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        const val CHANNEL_ID = "export_channel"
        const val NOTIFICATION_ID = 101
        
        fun startExport(
            context: Context,
            inputPath: String,
            isDeblur: Boolean,
            isEnhance: Boolean,
            scale: Int,
            sharpen: Float,
            texture: Float,
            vignette: Float,
            matrix: FloatArray
        ) {
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra("inputPath", inputPath)
                putExtra("isDeblur", isDeblur)
                putExtra("isEnhance", isEnhance)
                putExtra("scale", scale)
                putExtra("sharpen", sharpen)
                putExtra("texture", texture)
                putExtra("vignette", vignette)
                putExtra("matrix", matrix)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Iniciando procesamiento...", 0, 100))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val inputPath = it.getStringExtra("inputPath") ?: return START_NOT_STICKY
            val isDeblur = it.getBooleanExtra("isDeblur", false)
            val isEnhance = it.getBooleanExtra("isEnhance", false)
            val scale = it.getIntExtra("scale", 2)
            val sharpen = it.getFloatExtra("sharpen", 0f)
            val texture = it.getFloatExtra("texture", 0f)
            val vignette = it.getFloatExtra("vignette", 0f)
            val matrix = it.getFloatArrayExtra("matrix") ?: FloatArray(20)

            serviceScope.launch {
                processAndSave(inputPath, isDeblur, isEnhance, scale, sharpen, texture, vignette, matrix)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processAndSave(
        inputPath: String,
        isDeblur: Boolean,
        isEnhance: Boolean,
        scale: Int,
        sharpen: Float,
        texture: Float,
        vignette: Float,
        matrix: FloatArray
    ) {
        try {
            updateNotification("Cargando imagen...", 0, 100)
            val options = BitmapFactory.Options().apply { inMutable = true }
            var bitmap = BitmapFactory.decodeFile(inputPath, options) ?: return

            val upscaler = AiUpscaler()

            // Ejecutar el Pipeline Híbrido (Real-ESRGAN x4 + GFPGAN)
            val result = upscaler.processPipeline(
                context = this,
                inputBitmap = bitmap,
                onProgress = { msg, progress ->
                    updateNotification(msg, progress, 100)
                }
            )

            if (result != null) {
                bitmap.recycle()
                bitmap = result
            }

            // 3. PASO 3: Efectos Finales (Nitidez y Color sobre imagen fotorrealista)
            updateNotification("Ajustando detalles finales...", 95, 100)
            val processor = ImageProcessor()
            val composeMatrix = androidx.compose.ui.graphics.ColorMatrix(matrix)
            val finalBitmap = processor.applyFinalEffects(
                this, bitmap, composeMatrix, vignette, sharpen * 20f, texture * 20f
            )

            // 4. Guardar
            updateNotification("Guardando en Galería...", 95, 100)
            saveToGallery(finalBitmap)
            
            // Limpieza
            File(inputPath).delete()
            
        } catch (e: Exception) {
            Log.e("ExportService", "Error: ${e.message}")
            showFinishedNotification("Error en SiloEdit: ${e.message}")
        }
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val filename = "SiloEdit_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SiloEdit")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }
        }
        showFinishedNotification("¡Imagen guardada con éxito!")
    }

    private fun createNotification(text: String, progress: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SiloEdit Studio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(total, progress, false)
            .build()
    }

    private fun updateNotification(text: String, progress: Int, total: Int) {
        val notification = createNotification(text, progress, total)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showFinishedNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Procesamiento Completado")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        manager.notify(102, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "IA Processing", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
