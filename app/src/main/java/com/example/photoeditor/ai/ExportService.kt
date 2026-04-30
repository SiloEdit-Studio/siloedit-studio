package com.example.photoeditor.ai

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class ExportService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()
    
    companion object {
        const val CHANNEL_ID = "export_channel"
        const val NOTIFICATION_ID = 101
        
        fun startExport(
            context: Context,
            inputPath: String,
            isAiEnhance: Boolean,
            isCleanup: Boolean,
            scale: Int,
            sharpen: Float,
            texture: Float,
            vignette: Float,
            blur: Float,
            useCloud: Boolean,
            matrix: FloatArray,
            focusY: Float = 0.8f
        ) {
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra("inputPath", inputPath)
                putExtra("isAiEnhance", isAiEnhance)
                putExtra("isCleanup", isCleanup)
                putExtra("scale", scale)
                putExtra("sharpen", sharpen)
                putExtra("texture", texture)
                putExtra("vignette", vignette)
                putExtra("blur", blur)
                putExtra("useCloud", useCloud)
                putExtra("matrix", matrix)
                putExtra("focusY", focusY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        suspend fun exportCloudOnly(context: Context, bitmap: Bitmap, blurRadius: Float, focusY: Float = 0.8f): Bitmap? {
            val client = OkHttpClient()
            val tempFile = File(context.cacheDir, "photoroom_preview.jpg")
            return try {
                withContext(Dispatchers.IO) {
                    FileOutputStream(tempFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("imageFile", tempFile.name, tempFile.asRequestBody("image/jpeg".toMediaType()))
                        .addFormDataPart("removeBackground", "true")
                        .addFormDataPart("outputFormat", "png")
                        .build()
                        
                    val request = Request.Builder()
                        .url("https://image-api.photoroom.com/v2/edit")
                        .addHeader("x-api-key", "sk_pr_test_fb6193d04d978bfe5d34ef21a86d0e51a917b73b")
                        .post(requestBody)
                        .build()
                        
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val subjectPng = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (subjectPng != null) {
                                val processor = ImageProcessor()
                                val result = processor.applySelectiveBlur(bitmap, subjectPng, blurRadius, isNaturalDepth = true, focusY = focusY)
                                subjectPng.recycle()
                                result
                            } else null
                        } else null
                    } else null
                }
            } catch (e: Exception) { null } finally { tempFile.delete() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Preparando exportación...", 0, 100))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val inputPath = it.getStringExtra("inputPath") ?: return START_NOT_STICKY
            val isAiEnhance = it.getBooleanExtra("isAiEnhance", false)
            val isCleanup = it.getBooleanExtra("isCleanup", false)
            val scale = it.getIntExtra("scale", 1)
            val sharpen = it.getFloatExtra("sharpen", 5f)
            val texture = it.getFloatExtra("texture", 5f)
            val vignette = it.getFloatExtra("vignette", 0f)
            val blur = it.getFloatExtra("blur", 0f)
            val useCloud = it.getBooleanExtra("useCloud", false)
            val matrix = it.getFloatArrayExtra("matrix") ?: FloatArray(20)
            val focusY = it.getFloatExtra("focusY", 0.8f)

            serviceScope.launch {
                processAndSave(inputPath, isAiEnhance, isCleanup, scale, sharpen, texture, vignette, blur, useCloud, matrix, focusY)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processAndSave(
        inputPath: String,
        isAiEnhance: Boolean,
        isCleanup: Boolean,
        scale: Int,
        sharpen: Float,
        texture: Float,
        vignette: Float,
        blur: Float,
        useCloud: Boolean,
        matrix: FloatArray,
        focusY: Float
    ) {
        try {
            updateNotification("Cargando imagen...", 10, 100)
            val options = BitmapFactory.Options().apply { inMutable = true }
            var bitmap = BitmapFactory.decodeFile(inputPath, options) ?: return

            // 1. DESENFOQUE (Cloud o InSPyReNet Local)
            if (blur > 0f) {
                if (useCloud) {
                    updateNotification("PhotoRoom Matting...", 20, 100)
                    val cloudResult = processPhotoRoomMatting(bitmap)
                    if (cloudResult != null) {
                        val processor = ImageProcessor()
                        val blurred = processor.applySelectiveBlur(bitmap, cloudResult, blur, isNaturalDepth = true, focusY = focusY)
                        bitmap.recycle()
                        cloudResult.recycle()
                        bitmap = blurred
                    }
                } else {
                    updateNotification("IA Portrait Matting...", 20, 100)
                    val segmentation = AiSegmentation(this)
                    if (segmentation.loadModel()) {
                        val mask = segmentation.getPersonMask(bitmap)
                        if (mask != null) {
                            val processor = ImageProcessor()
                            val blurred = processor.applySelectiveBlur(bitmap, mask, blur, isNaturalDepth = true, focusY = focusY)
                            bitmap.recycle()
                            mask.recycle()
                            bitmap = blurred
                        }
                    }
                    segmentation.close()
                }
            }

            // 2. PIPELINE IA (Limpieza y Upscaler)
            if (isAiEnhance || isCleanup || scale > 1) {
                updateNotification("Reconstrucción IA...", 50, 100)
                val upscaler = AiUpscaler()
                val aiResult = upscaler.processPipeline(
                    context = this,
                    inputBitmap = bitmap,
                    isAiEnhance = isAiEnhance,
                    isCleanup = isCleanup,
                    targetScale = scale,
                    onProgress = { msg, prog ->
                        updateNotification(msg, 50 + (prog * 0.4).toInt(), 100)
                    }
                )
                if (aiResult != null) {
                    if (bitmap != aiResult) bitmap.recycle()
                    bitmap = aiResult
                }
            }

            // 3. EFECTOS FINALES
            updateNotification("Finalizando detalles...", 95, 100)
            val processor = ImageProcessor()
            val composeMatrix = androidx.compose.ui.graphics.ColorMatrix(matrix)
            val finalBitmap = processor.applyFinalEffects(
                this, bitmap, composeMatrix, vignette, sharpen * 10f, texture * 10f
            )

            saveToGallery(finalBitmap)
            File(inputPath).delete()
            
        } catch (e: Exception) {
            Log.e("ExportService", "Error: ${e.message}")
            showFinishedNotification("Error en la exportación.")
        }
    }

    private suspend fun processPhotoRoomMatting(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(cacheDir, "photoroom_matting.jpg")
            FileOutputStream(tempFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("imageFile", tempFile.name, tempFile.asRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("removeBackground", "true")
                .addFormDataPart("outputFormat", "png")
                .build()
            val request = Request.Builder()
                .url("https://image-api.photoroom.com/v2/edit")
                .addHeader("x-api-key", "sk_pr_test_fb6193d04d978bfe5d34ef21a86d0e51a917b73b")
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                tempFile.delete()
                if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) { null }
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val filename = "SiloEdit_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SiloEdit")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 98, stream)
            }
        }
        showFinishedNotification("¡Imagen guardada en Galería!")
    }

    private fun createNotification(text: String, progress: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SiloEdit Studio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
            .setContentTitle("SiloEdit")
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
