package com.example.photoeditor.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Environment
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

// Colores Profesionales (Estilo Lightroom Dark)
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
    var sharpenAmountSlider by remember { mutableFloatStateOf(0f) }
    var textureAmountSlider by remember { mutableFloatStateOf(0f) }
    
    // Estado IA (Persistente para el Exportador)
    var isUpscaleEnabled by remember { mutableStateOf(false) }
    var isEnhanceEnabled by remember { mutableStateOf(false) }
    var isDeblurEnabled by remember { mutableStateOf(false) }
    var selectedScale by remember { mutableIntStateOf(2) }
    var isSavingProcess by remember { mutableStateOf(false) }

    var isUIVisible by remember { mutableStateOf(true) }
    var expandedSection by remember { mutableStateOf("") }
    var isComparing by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        if (!isComparing) {
            scale = (scale * zoomChange).coerceIn(1f, 8f)
            offset += offsetChange
        }
    }

    val finalMatrix = remember(exposure, contrast, saturation, vibrance, temp, tint, highlights, shadows, blancos, negros) {
        processor.getLightroomMatrix(exposure, contrast, saturation, vibrance, temp, tint, highlights, shadows, blancos, negros)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it)) { d, _, _ -> d.isMutableRequired = true }
            } else {
                MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            }
            viewModel.setBitmap(bitmap)
            isUpscaleEnabled = false
            isEnhanceEnabled = false
        }
    }

    fun savePhoto() {
        val baseBitmap = currentBitmap ?: return
        isSavingProcess = true

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Guardar bitmap original en archivo temporal
                val tempFile = File(context.cacheDir, "temp_export.jpg")
                FileOutputStream(tempFile).use { 
                    baseBitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                }

                // 2. Iniciar Servicio de Exportación en Foreground
                ExportService.startExport(
                    context = context,
                    inputPath = tempFile.absolutePath,
                    isDeblur = isDeblurEnabled,
                    isEnhance = isEnhanceEnabled,
                    scale = selectedScale,
                    sharpen = sharpenAmountSlider,
                    texture = textureAmountSlider,
                    vignette = vignette,
                    matrix = finalMatrix.values
                )
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Procesando en segundo plano...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AiUpscaler.logToFile(context, "ERROR AL INICIAR SERVICIO: ${e.message}")
            } finally {
                isSavingProcess = false
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentBitmap != null) {
                AnimatedVisibility(visible = isUIVisible, enter = fadeIn(), exit = fadeOut()) {
                    TopAppBar(
                        title = { Text("SiloEdit Studio", color = LightroomGray, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                        actions = {
                            IconButton(onClick = { launcher.launch("image/*") }) {
                                Icon(Icons.Default.AddPhotoAlternate, "Cambiar", tint = LightroomGray)
                            }
                            if (isSavingProcess) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = LightroomBlue, strokeWidth = 2.dp)
                                Spacer(Modifier.width(16.dp))
                            } else {
                                TextButton(onClick = { savePhoto() }) {
                                    Icon(Icons.Default.Save, null, tint = LightroomBlue)
                                    Spacer(Modifier.width(4.dp))
                                    Text("EXPORTAR", color = LightroomBlue, fontWeight = FontWeight.Bold)
                                }
                            }
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
                            Surface(modifier = Modifier.fillMaxWidth().height(340.dp), color = PanelBackground) {
                                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    when (expandedSection) {
                                        "Luz" -> {
                                            EditorSection("Panel de Luz", true, { expandedSection = "" }) {
                                                LightroomSlider("Exposición", exposure, -100f..100f, { exposure = it })
                                                LightroomSlider("Contraste", contrast, 0.5f..2.0f, { contrast = it })
                                                LightroomSlider("Iluminaciones", highlights, -100f..100f, { highlights = it })
                                                LightroomSlider("Sombras", shadows, -100f..100f, { shadows = it })
                                                LightroomSlider("Blancos", blancos, -100f..100f, { blancos = it })
                                                LightroomSlider("Negros", negros, -100f..100f, { negros = it })
                                            }
                                        }
                                        "Color" -> {
                                            EditorSection("Color y Presencia", true, { expandedSection = "" }) {
                                                LightroomSlider("Temperatura", temp, -50f..50f, { temp = it })
                                                LightroomSlider("Matiz", tint, -50f..50f, { tint = it })
                                                LightroomSlider("Intensidad", vibrance, 0f..100f, { vibrance = it })
                                                LightroomSlider("Saturación", saturation, 0f..2f, { saturation = it })
                                            }
                                        }
                                        "Efectos" -> {
                                            EditorSection("Inteligencia Artificial Local", true, { expandedSection = "" }) {
                                                Text("MEJORA INTELIGENTE", color = LightroomBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(8.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    OutlinedButton(
                                                        onClick = { isEnhanceEnabled = !isEnhanceEnabled },
                                                        border = if (isEnhanceEnabled) BorderStroke(2.dp, Color(0xFF00C853)) else BorderStroke(1.dp, Color.Gray),
                                                        modifier = Modifier.weight(1f),
                                                        colors = ButtonDefaults.outlinedButtonColors(
                                                            containerColor = if (isEnhanceEnabled) Color(0xFF00C853).copy(0.1f) else Color.Transparent
                                                        )
                                                    ) {
                                                        Icon(Icons.Default.AutoFixNormal, null, tint = if (isEnhanceEnabled) Color(0xFF00C853) else Color.Gray, modifier = Modifier.size(18.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("LIMPIEZA", color = if (isEnhanceEnabled) Color.White else Color.Gray, fontSize = 12.sp)
                                                    }

                                                    OutlinedButton(
                                                        onClick = { isDeblurEnabled = !isDeblurEnabled },
                                                        border = if (isDeblurEnabled) BorderStroke(2.dp, Color(0xFF2196F3)) else BorderStroke(1.dp, Color.Gray),
                                                        modifier = Modifier.weight(1f),
                                                        colors = ButtonDefaults.outlinedButtonColors(
                                                            containerColor = if (isDeblurEnabled) Color(0xFF2196F3).copy(0.1f) else Color.Transparent
                                                        )
                                                    ) {
                                                        Icon(Icons.Default.BlurOff, null, tint = if (isDeblurEnabled) Color(0xFF2196F3) else Color.Gray, modifier = Modifier.size(18.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("DEBLUR", color = if (isDeblurEnabled) Color.White else Color.Gray, fontSize = 12.sp)
                                                    }
                                                }

                                                Spacer(Modifier.height(16.dp))
                                                Text("ESCALADO (PIXELS)", color = LightroomBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(8.dp))
                                                
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    OutlinedButton(
                                                        onClick = { 
                                                            isUpscaleEnabled = !isUpscaleEnabled
                                                            selectedScale = 2
                                                        },
                                                        border = if (isUpscaleEnabled && selectedScale == 2) BorderStroke(2.dp, LightroomBlue) else BorderStroke(1.dp, Color.Gray),
                                                        modifier = Modifier.weight(1f),
                                                        colors = ButtonDefaults.outlinedButtonColors(
                                                            containerColor = if (isUpscaleEnabled && selectedScale == 2) LightroomBlue.copy(0.2f) else Color.Transparent
                                                        )
                                                    ) {
                                                        Text("2X (Local)", color = if (isUpscaleEnabled && selectedScale == 2) Color.White else Color.Gray)
                                                    }
                                                    
                                                    OutlinedButton(
                                                        onClick = { 
                                                            isUpscaleEnabled = !isUpscaleEnabled
                                                            selectedScale = 4
                                                        },
                                                        border = if (isUpscaleEnabled && selectedScale == 4) BorderStroke(2.dp, LightroomBlue) else BorderStroke(1.dp, Color.Gray),
                                                        modifier = Modifier.weight(1f),
                                                        colors = ButtonDefaults.outlinedButtonColors(
                                                            containerColor = if (isUpscaleEnabled && selectedScale == 4) LightroomBlue.copy(0.2f) else Color.Transparent
                                                        )
                                                    ) {
                                                        Text("4X (Local)", color = if (isUpscaleEnabled && selectedScale == 4) Color.White else Color.Gray)
                                                    }
                                                }
                                                
                                                if (isUpscaleEnabled || isEnhanceEnabled) {
                                                    Text(
                                                        "✨ Se aplicará la mejora al Exportar.",
                                                        color = LightroomBlue,
                                                        fontSize = 12.sp,
                                                        modifier = Modifier.padding(top = 8.dp)
                                                    )
                                                }

                                                Spacer(Modifier.height(16.dp))
                                                LightroomSlider("Nitidez Final", sharpenAmountSlider, 0f..5f, { sharpenAmountSlider = it })
                                                LightroomSlider("Textura IA", textureAmountSlider, 0f..5f, { textureAmountSlider = it })
                                                LightroomSlider("Viñeta", vignette, -100f..100f, { vignette = it })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Barra de Herramientas
                        Row(
                            modifier = Modifier.fillMaxWidth().height(70.dp).background(Color.Black),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ToolIcon(Icons.Default.Tune, "Luz", expandedSection == "Luz") { expandedSection = if (expandedSection == "Luz") "" else "Luz" }
                            ToolIcon(Icons.Default.Palette, "Color", expandedSection == "Color") { expandedSection = if (expandedSection == "Color") "" else "Color" }
                            ToolIcon(Icons.Default.AutoFixHigh, "IA", expandedSection == "Efectos") { expandedSection = if (expandedSection == "Efectos") "" else "Efectos" }
                            IconButton(onClick = {
                                exposure = 0f
                                contrast = 1f
                                highlights = 0f
                                shadows = 0f
                                blancos = 0f
                                negros = 0f
                                temp = 0f
                                tint = 0f
                                vibrance = 0f
                                saturation = 1f
                                vignette = 0f
                                sharpenAmountSlider = 0f
                                textureAmountSlider = 0f
                                isUpscaleEnabled = false
                                isEnhanceEnabled = false
                                isDeblurEnabled = false
                            }) {
                                Icon(Icons.Default.Refresh, "Reset", tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (currentBitmap != null) isUIVisible = !isUIVisible },
                        onLongPress = { isComparing = true },
                        onPress = {
                            val released = tryAwaitRelease()
                            if (released) isComparing = false
                        }
                    )
                }
                .transformable(state = transformState),
            contentAlignment = Alignment.Center
        ) {
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    colorFilter = if (isComparing) null else ColorFilter.colorMatrix(finalMatrix)
                )
                
                if (isSavingProcess) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = LightroomBlue)
                            Spacer(Modifier.height(16.dp))
                            Text("RECONSTRUYENDO CON IA...", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Esto puede tardar 20-40 seg.", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(alpha = 0.99f)) {
                    if (!isComparing && vignette != 0f) {
                        val radius = size.minDimension / 1.5f
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                                center = center,
                                radius = radius * (1.3f - (Math.abs(vignette) / 100f))
                            ),
                            radius = size.maxDimension,
                            alpha = (Math.abs(vignette) / 100f)
                        )
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(64.dp), tint = Color.DarkGray)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { launcher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = LightroomBlue)
                    ) {
                        Text("ABRIR GALERÍA", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun EditorSection(title: String, isExpanded: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = LightroomGray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.Gray)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            content()
        }
        HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
    }
}

@Composable
fun LightroomSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(if (range.endInclusive > 5f || range.start < -5f) value.toInt().toString() else "%.2f".format(value), 
                color = LightroomGray, fontSize = 11.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = LightroomGray,
                activeTrackColor = LightroomBlue,
                inactiveTrackColor = Color.DarkGray
            )
        )
    }
}

@Composable
fun ToolIcon(icon: ImageVector, label: String, isSelected: Boolean = false, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(8.dp)
    ) {
        Icon(icon, label, tint = if (isSelected) LightroomBlue else Color.Gray, modifier = Modifier.size(28.dp))
        Text(label, color = if (isSelected) LightroomBlue else Color.Gray, fontSize = 10.sp)
    }
}
