package com.mozhi.reader.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.mozhi.reader.core.media.EpubImageFiles
import com.mozhi.reader.feature.reader.engine.ReaderPageImage
import com.mozhi.reader.ui.components.ImageExportActions
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val ImageEditorColors = darkColorScheme(
    primary = Color(0xFFE0E9D0), onPrimary = Color(0xFF243020),
    background = Color(0xFF101211), surface = Color(0xFF1D211F),
    onSurface = Color(0xFFF0F2EC), onSurfaceVariant = Color(0xFFB7BDB5),
    outlineVariant = Color(0xFF343B35)
)

/** Dedicated modal: transformations never alter pagination, original assets or reading progress. */
@Composable
internal fun ReaderEpubImageDialog(image: ReaderPageImage, onDismiss: () -> Unit, onLocate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var source by remember(image.imagePath) { mutableStateOf<File?>(null) }
    var bitmap by remember(image.imagePath) { mutableStateOf<Bitmap?>(null) }
    var error by remember(image.imagePath) { mutableStateOf<String?>(null) }
    var exportPath by remember(image.imagePath) { mutableStateOf<String?>(null) }
    var quarterTurns by rememberSaveable(image.imagePath) { mutableIntStateOf(0) }
    var transform by remember(image.imagePath) { mutableStateOf(ReaderImageTransform(quarterTurns = quarterTurns)) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(image.imagePath) {
        try {
            val file = EpubImageFiles.materialize(context, image.imagePath)
            source = file
            bitmap = EpubImageFiles.preview(file)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法打开图片，文件可能已损坏或格式暂不支持" }
    }
    // RenderThread can retain a frame after disposal; the bounded preview is reclaimed by GC.
    LaunchedEffect(source, quarterTurns) {
        exportPath = null
        val file = source ?: return@LaunchedEffect
        try {
            exportPath = EpubImageFiles.export(file, quarterTurns).absolutePath
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { snackbar.showSnackbar("无法准备保存文件，请重置后重试") }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val bars = window?.let { WindowCompat.getInsetsController(it, dialogView) }
            val lightStatus = bars?.isAppearanceLightStatusBars ?: false
            val lightNavigation = bars?.isAppearanceLightNavigationBars ?: false
            bars?.isAppearanceLightStatusBars = false
            bars?.isAppearanceLightNavigationBars = false
            onDispose {
                bars?.isAppearanceLightStatusBars = lightStatus
                bars?.isAppearanceLightNavigationBars = lightNavigation
            }
        }
        MaterialTheme(colorScheme = ImageEditorColors) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("epub-image-editor"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center) {
                        Surface(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
                            shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回阅读", Modifier.size(22.dp))
                            }
                        }
                        Text("图片编辑", style = MaterialTheme.typography.titleMedium)
                        TextButton(enabled = bitmap != null, modifier = Modifier.align(Alignment.CenterEnd), onClick = {
                            transform = ReaderImageTransform()
                            quarterTurns = 0
                        }) { Text("重置", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    val pixels = bitmap?.takeUnless { it.isRecycled }?.asImageBitmap()
                    val imageSize = pixels?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clipToBounds()
                        .onSizeChanged {
                            viewport = Size(it.width.toFloat(), it.height.toFloat())
                            transform = transform.reset()
                        }.testTag("epub-image-viewport"), contentAlignment = Alignment.Center) {
                        if (pixels != null) Canvas(Modifier.fillMaxSize()
                            .semantics {
                                contentDescription = image.altText.ifBlank { "书内图片" }
                                stateDescription = "缩放${(transform.zoom * 100).toInt()}%，旋转${transform.quarterTurns * 90}度"
                            }
                            .pointerInput(pixels) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    transform = transform.gesture(centroid, pan, zoom, imageSize, viewport)
                                }
                            }.pointerInput(pixels) {
                                detectTapGestures(onDoubleTap = { position ->
                                    transform = if (transform.zoom > 1.01f) transform.reset()
                                    else transform.gesture(position, Offset.Zero, 2.5f, imageSize, viewport)
                                })
                            }) {
                            val scale = transform.fit(imageSize, size) * transform.zoom
                            withTransform({
                                translate(size.width / 2f + transform.offset.x, size.height / 2f + transform.offset.y)
                                rotate(transform.quarterTurns * 90f, Offset.Zero)
                                scale(scale, scale, Offset.Zero)
                            }) { drawImage(pixels, topLeft = Offset(-imageSize.width / 2f, -imageSize.height / 2f)) }
                        } else if (error == null) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        else Text(error!!, modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                    }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
                        modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)) {
                        Text("${(transform.zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp))
                    }
                    Surface(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)
                        .widthIn(max = 440.dp).fillMaxWidth().testTag("epub-image-toolbar"),
                        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
                        shadowElevation = 16.dp) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ImageEditorTool("定位原文", Icons.Outlined.MyLocation, onClick = onLocate,
                                modifier = Modifier.weight(1f))
                            ImageEditorTool("旋转", Icons.AutoMirrored.Outlined.RotateRight,
                                enabled = pixels != null, modifier = Modifier.weight(1f), onClick = {
                                    transform = transform.rotate()
                                    quarterTurns = transform.quarterTurns
                                })
                            ImageExportActions(exportPath, modifier = Modifier.weight(1f),
                                onResult = { message -> scope.launch { snackbar.showSnackbar(message) } },
                                menuTrigger = { enabled, working, open ->
                                    ImageEditorTool("保存", Icons.Outlined.FileDownload,
                                        modifier = Modifier.fillMaxWidth(), enabled = enabled,
                                        highlighted = true, working = working, onClick = open)
                                })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageEditorTool(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    working: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val foreground = if (highlighted) colors.onPrimary else colors.onSurface
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 72.dp),
        shape = RoundedCornerShape(20.dp), color = if (highlighted) colors.primary else Color.Transparent,
        contentColor = foreground.copy(alpha = if (enabled || working) 1f else .4f)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (working) CircularProgressIndicator(Modifier.size(25.dp), color = foreground, strokeWidth = 2.dp)
            else Icon(icon, null, Modifier.size(25.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
