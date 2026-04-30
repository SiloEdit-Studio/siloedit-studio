package com.example.photoeditor.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photoeditor.ai.AiUpscaler
import com.example.photoeditor.ai.ExportService
import com.example.photoeditor.ai.ImageProcessor
import java.io.File
import java.io.FileOutputStream
import com.example.photoeditor.ui.PhotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LightroomBlue = Color(0xFF2B5EA7)
val LightroomGray = Color(0xFFE0E0E0)
val PanelBackground = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: PhotoViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentBitmap by viewModel.currentBitmap.collectAsState()
    val processor = remember { ImageProcessor() }

    // --- ESTADO DE EDICIÓN ---
    var exposure by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var highlights by remember { mutableFloatStateOf(0f) }
    var shadows by remember { mutableFloatStateOf(0f) }
    var blancos by remember { mutableFloatStateOf(0f) }
    var negros by remember { mutableFloatStateOf(0f) }
    var temp by remember { mutableFloatStateOf(0f) }
    var tint by remember { mutableFloatStateOf(0f) }
    var vibrance by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var vignette by remember { mutableFloatStateOf(0f) }
    
    // IA & Mejoras
    var sharpenAmountSlider by remember { mutableFloatStateOf(5f) }
    var textureAmountSlider by remember { mutableFloatStateOf(5f) }
    
    // Estado IA
    var isUpscaleEnabled by remember { mutableStateOf(false) }
    var isEnhanceEnabled by remember { mutableStateOf(true) }
    var isCleanupEnabled by remember { mutableStateOf(false) }
    var selectedScale by remember { mutableIntStateOf(1) }
    var isSavingProcess by remember { mutableStateOf(false) }

    // Retrato y Fondo
    var blurAmountSlider by remember { mutableFloatStateOf(0f) }
    var focusY by remember { mutableFloatStateOf(0.8f) }
    var blurMask by remember { mutableStateOf<Bitmap?>(null) }
    var manualMask by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessingPortrait by remember { mutableStateOf(false) }
    var isBrushMode by remember { mutableStateOf(false) }
    var isFocusSelectMode by remember { mutableStateOf(false) }
    var brushSize by remember { mutableFloatStateOf(60f) }
    var isErasing by remember { mutableStateOf(false) }
    var maskUpdateTick by remember { mutableIntStateOf(0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    var isUIVisible by remember { mutableStateOf(true) }
    var expandedSection by remember { mutableStateOf("") }
    var isComparing by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        if (!isComparing && !isBrushMode && !isFocusSelectMode) {
            scale = (scale * zoomChange).coerceIn(1f, 8f)
            offset += offsetChange
        }
    }

    val finalMatrix = remember(exposure, contrast, saturation, vibrance, temp, tint, highlights, shadows, blancos, negros) {
        processor.getLightroomMatrix(exposure, contrast, saturation, vibrance, temp, tint, highlights, shadows, blancos, negros)
    }

    // Preview ligero
    val previewBitmap = remember(currentBitmap) {
        currentBitmap?.let {
            val factor = 1024f / Math.max(it.width, it.height).coerceAtLeast(1)
            if (factor < 1f) Bitmap.createScaledBitmap(it, (it.width * factor).toInt(), (it.height * factor).toInt(), true) else it
        }
    }

    val blurredPreviewBitmap = remember(previewBitmap, blurAmountSlider, blurMask, manualMask, maskUpdateTick, focusY) {
        if (previewBitmap != null && blurAmountSlider > 0) {
            val baseMask = blurMask ?: Bitmap.createBitmap(previewBitmap.width, previewBitmap.height, Bitmap.Config.ARGB_8888)
            val finalMask = if (manualMask != null) {
                val combined = baseMask.copy(baseMask.config ?: Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(combined)
                val scaledManual = Bitmap.createScaledBitmap(manualMask!!, baseMask.width, baseMask.height, true)
                canvas.drawBitmap(scaledManual, 0f, 0f, null)
                scaledManual.recycle()
                combined
            } else baseMask
            val result = processor.applySelectiveBlur(previewBitmap, finalMask, blurAmountSlider / 2f, isNaturalDepth = true, focusY = focusY)
            if (finalMask != baseMask) finalMask.recycle()
            result
        } else null
    }

    fun updateManualMask(touchOffset: Offset) {
        if (currentBitmap == null || boxSize == IntSize.Zero) return
        if (manualMask == null) manualMask = Bitmap.createBitmap(currentBitmap!!.width, currentBitmap!!.height, Bitmap.Config.ARGB_8888)
        
        val containerW = boxSize.width.toFloat()
        val containerH = boxSize.height.toFloat()
        val centerX = containerW / 2f; val centerY = containerH / 2f
        val xInContainer = (touchOffset.x - offset.x - centerX) / scale + centerX
        val yInContainer = (touchOffset.y - offset.y - centerY) / scale + centerY

        val fitScale = Math.min(containerW / currentBitmap!!.width, containerH / currentBitmap!!.height)
        val mappedX = (xInContainer - (containerW - currentBitmap!!.width * fitScale) / 2) / fitScale
        val mappedY = (yInContainer - (containerH - currentBitmap!!.height * fitScale) / 2) / fitScale
        val mappedSize = brushSize / (fitScale * scale)

        val canvas = android.graphics.Canvas(manualMask!!)
        val paint = android.graphics.Paint().apply {
            color = if (isErasing) android.graphics.Color.TRANSPARENT else android.graphics.Color.WHITE
            xfermode = if (isErasing) android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) else null
            strokeWidth = mappedSize; strokeCap = android.graphics.Paint.Cap.ROUND; isAntiAlias = true
        }
        canvas.drawCircle(mappedX, mappedY, mappedSize / 2f, paint)
        maskUpdateTick++
    }

    fun setFocusPoint(touchOffset: Offset) {
        if (currentBitmap == null || boxSize == IntSize.Zero) return
        val containerH = boxSize.height.toFloat()
        val centerY = containerH / 2f
        val yInContainer = (touchOffset.y - offset.y - centerY) / scale + centerY
        
        val bmpH = currentBitmap!!.height.toFloat()
        val fitScale = Math.min(boxSize.width.toFloat() / currentBitmap!!.width, containerH / bmpH)
        val contentY = (containerH - bmpH * fitScale) / 2
        
        val mappedY = (yInContainer - contentY) / fitScale
        focusY = (mappedY / bmpH).coerceIn(0f, 1f)
        maskUpdateTick++
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it)) { d, _, _ -> d.isMutableRequired = true }
            } else MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            viewModel.setBitmap(bitmap)
            blurMask = null; manualMask = null; focusY = 0.8f
        }
    }

    fun savePhoto() {
        val baseBitmap = currentBitmap ?: return
        isSavingProcess = true
        scope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "temp_export.jpg")
                FileOutputStream(tempFile).use { baseBitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                ExportService.startExport(
                    context = context,
                    inputPath = tempFile.absolutePath,
                    isAiEnhance = isEnhanceEnabled,
                    isCleanup = isCleanupEnabled,
                    scale = if (isUpscaleEnabled) selectedScale else 1,
                    sharpen = sharpenAmountSlider,
                    texture = textureAmountSlider,
                    vignette = vignette,
                    blur = blurAmountSlider,
                    useCloud = false, // PhotoRoom eliminado
                    matrix = finalMatrix.values,
                    focusY = focusY
                )
                withContext(Dispatchers.Main) { Toast.makeText(context, "Exportando...", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { e.printStackTrace() } finally { isSavingProcess = false }
        }
    }

    Scaffold(
        topBar = {
            if (currentBitmap != null) {
                AnimatedVisibility(visible = isUIVisible, enter = fadeIn(), exit = fadeOut()) {
                    TopAppBar(
                        title = { Text("SiloEdit PRO", color = LightroomGray, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                        actions = {
                            IconButton(onClick = { launcher.launch("image/*") }) { Icon(Icons.Default.AddPhotoAlternate, null, tint = LightroomGray) }
                            if (isSavingProcess) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = LightroomBlue)
                            else TextButton(onClick = { savePhoto() }) { Text("EXPORTAR", color = LightroomBlue, fontWeight = FontWeight.Bold) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                    )
                }
            }
        },
        bottomBar = {
            if (currentBitmap != null) {
                AnimatedVisibility(visible = isUIVisible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    Column(modifier = Modifier.background(Color.Black)) {
                        if (expandedSection.isNotEmpty()) {
                            Surface(modifier = Modifier.fillMaxWidth().height(360.dp), color = PanelBackground) {
                                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    when (expandedSection) {
                                        "Luz" -> {
                                            EditorSection("Panel de Luz", true, { expandedSection = "" }) {
                                                LightroomSlider("Exposición", exposure, -100f..100f, { exposure = it })
                                                LightroomSlider("Contraste", contrast, 0.5f..2.0f, { contrast = it })
                                                LightroomSlider("Iluminaciones", highlights, -100f..100f, { highlights = it })
                                                LightroomSlider("Sombras", shadows, -100f..100f, { shadows = it })
                                            }
                                        }
                                        "Color" -> {
                                            EditorSection("Color y Balance", true, { expandedSection = "" }) {
                                                LightroomSlider("Temperatura", temp, -50f..50f, { temp = it })
                                                LightroomSlider("Matiz", tint, -50f..50f, { tint = it })
                                                LightroomSlider("Saturación", saturation, 0f..2f, { saturation = it })
                                            }
                                        }
                                        "Fondo" -> {
                                            EditorSection("Modo Retrato PRO", true, { expandedSection = "" }) {
                                                Text("DETECCIÓN DE SUJETO", color = LightroomBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(8.dp))

                                                Button(
                                                    onClick = {
                                                        scope.launch {
                                                            isProcessingPortrait = true
                                                            AiUpscaler.logToFile(context, "UI: Iniciando Recorte IA...")
                                                            val seg = com.example.photoeditor.ai.AiSegmentation(context)
                                                            if (seg.loadModel()) {
                                                                val mask = seg.getPersonMask(currentBitmap!!)
                                                                if (mask != null) {
                                                                    blurMask = mask
                                                                    if (blurAmountSlider == 0f) blurAmountSlider = 10f
                                                                    AiUpscaler.logToFile(context, "UI: Recorte finalizado.")
                                                                }
                                                            }
                                                            seg.close()
                                                            isProcessingPortrait = false
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    enabled = !isProcessingPortrait,
                                                    colors = ButtonDefaults.buttonColors(containerColor = LightroomBlue)
                                                ) {
                                                    if (isProcessingPortrait) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White) 
                                                    else Text("APLICAR RECORTE IA")
                                                }

                                                Spacer(Modifier.height(16.dp))
                                                Text("DESENFOQUE NATURAL", color = if (blurMask != null) LightroomBlue else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                
                                                LightroomSlider(
                                                    label = "Intensidad", 
                                                    value = blurAmountSlider, 
                                                    range = 0f..50f, 
                                                    onValueChange = { blurAmountSlider = it },
                                                    enabled = blurMask != null
                                                )

                                                OutlinedButton(
                                                    onClick = { isFocusSelectMode = !isFocusSelectMode; isBrushMode = false }, 
                                                    modifier = Modifier.fillMaxWidth(),
                                                    enabled = blurMask != null,
                                                    border = if(isFocusSelectMode) BorderStroke(2.dp, LightroomBlue) else null
                                                ) { 
                                                    Icon(Icons.Default.Adjust, null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("CAMBIAR PUNTO DE ENFOQUE", fontSize = 11.sp) 
                                                }

                                                Spacer(Modifier.height(16.dp))
                                                Text("RETOQUE MANUAL", color = if (blurMask != null) LightroomBlue else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                    OutlinedButton(
                                                        onClick = { isBrushMode = !isBrushMode; isFocusSelectMode = false }, 
                                                        modifier = Modifier.weight(1f), 
                                                        enabled = blurMask != null,
                                                        border = if(isBrushMode) BorderStroke(2.dp, LightroomBlue) else null
                                                    ) { Text("PINCEL") }
                                                    
                                                    OutlinedButton(
                                                        onClick = { isErasing = !isErasing }, 
                                                        modifier = Modifier.weight(1f), 
                                                        enabled = isBrushMode, 
                                                        border = if(isErasing) BorderStroke(2.dp, Color.Red) else null
                                                    ) { Text("BORRAR") }
                                                }
                                                if (isBrushMode) {
                                                    LightroomSlider("Tamaño Pincel", brushSize, 10f..200f, { brushSize = it })
                                                }
                                                if (isFocusSelectMode) {
                                                    Text("💡 Toca en la foto para fijar la zona nítida.", color = LightroomBlue, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                                                }
                                            }
                                        }
                                        "IA" -> {
                                            EditorSection("Mejora Artificial", true, { expandedSection = "" }) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(onClick = { isCleanupEnabled = !isCleanupEnabled }, modifier = Modifier.weight(1f), border = if(isCleanupEnabled) BorderStroke(2.dp, Color.Green) else null) { Text("LIMPIEZA") }
                                                    OutlinedButton(onClick = { isEnhanceEnabled = !isEnhanceEnabled }, modifier = Modifier.weight(1f), border = if(isEnhanceEnabled) BorderStroke(2.dp, LightroomBlue) else null) { Text("IA ADAPT") }
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(onClick = { isUpscaleEnabled = !isUpscaleEnabled; selectedScale = 2 }, modifier = Modifier.weight(1f), border = if(isUpscaleEnabled && selectedScale == 2) BorderStroke(2.dp, LightroomBlue) else null) { Text("2X") }
                                                    OutlinedButton(onClick = { isUpscaleEnabled = !isUpscaleEnabled; selectedScale = 4 }, modifier = Modifier.weight(1f), border = if(isUpscaleEnabled && selectedScale == 4) BorderStroke(2.dp, LightroomBlue) else null) { Text("4X") }
                                                }
                                                LightroomSlider("Nitidez", sharpenAmountSlider, 0f..5f, { sharpenAmountSlider = it })
                                                LightroomSlider("Textura", textureAmountSlider, 0f..5f, { textureAmountSlider = it })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().height(70.dp).background(Color.Black), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            ToolIcon(Icons.Default.Tune, "Luz", expandedSection == "Luz") { expandedSection = if (expandedSection == "Luz") "" else "Luz" }
                            ToolIcon(Icons.Default.Palette, "Color", expandedSection == "Color") { expandedSection = if (expandedSection == "Color") "" else "Color" }
                            ToolIcon(Icons.Default.Portrait, "Fondo", expandedSection == "Fondo") { expandedSection = if (expandedSection == "Fondo") "" else "Fondo" }
                            ToolIcon(Icons.Default.AutoFixHigh, "IA", expandedSection == "IA") { expandedSection = if (expandedSection == "IA") "" else "IA" }
                        }
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).onGloballyPositioned { boxSize = it.size }
            .pointerInput(isBrushMode, isFocusSelectMode, brushSize, isErasing, boxSize, scale, offset) {
                if (isBrushMode) {
                    detectDragGestures { change, _ -> updateManualMask(change.position) }
                    detectTapGestures { offset -> updateManualMask(offset) }
                } else if (isFocusSelectMode) {
                    detectTapGestures { offset -> setFocusPoint(offset) }
                } else {
                    detectTapGestures(onTap = { if (currentBitmap != null) isUIVisible = !isUIVisible }, onLongPress = { isComparing = true }, onPress = { if (tryAwaitRelease()) isComparing = false })
                }
            }
            .transformable(state = transformState, enabled = !isBrushMode && !isFocusSelectMode),
            contentAlignment = Alignment.Center
        ) {
            if (currentBitmap != null) {
                Image(bitmap = (blurredPreviewBitmap ?: previewBitmap ?: currentBitmap!!).asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    colorFilter = if (isComparing) null else ColorFilter.colorMatrix(finalMatrix))
                if (isProcessingPortrait) Box(Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LightroomBlue) }
            } else {
                Button(onClick = { launcher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = LightroomBlue)) { Text("ABRIR GALERÍA") }
            }
        }
    }
}

@Composable
fun EditorSection(title: String, isExpanded: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = LightroomGray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.Gray)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
        HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
    }
}

@Composable
fun LightroomSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit, enabled: Boolean = true) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) Color.Gray else Color.DarkGray, fontSize = 11.sp); Text("%.1f".format(value), color = if (enabled) LightroomGray else Color.DarkGray, fontSize = 11.sp)
        }
        Slider(
            value = value, 
            onValueChange = onValueChange, 
            valueRange = range, 
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) LightroomGray else Color.DarkGray, 
                activeTrackColor = if (enabled) LightroomBlue else Color.DarkGray,
                inactiveTrackColor = Color.DarkGray
            )
        )
    }
}

@Composable
fun ToolIcon(icon: ImageVector, label: String, isSelected: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(8.dp)) {
        Icon(icon, null, tint = if (isSelected) LightroomBlue else Color.Gray, modifier = Modifier.size(28.dp))
        Text(label, color = if (isSelected) LightroomBlue else Color.Gray, fontSize = 10.sp)
    }
}
