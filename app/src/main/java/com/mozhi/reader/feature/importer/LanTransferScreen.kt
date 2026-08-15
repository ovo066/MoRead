package com.mozhi.reader.feature.importer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mozhi.reader.core.importer.lan.LanReceivedFile
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop

/**
 * 局域网传书页：手机开服务、电脑浏览器上传。地址与二维码都摆在最显眼处，
 * 「没有密码」的事实也直说——这台服务对同网段是完全敞开的。
 */
@Composable
fun LanTransferScreen(
    onBack: () -> Unit,
    onImported: () -> Unit,
    viewModel: LanTransferViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var showQr by remember { mutableStateOf(false) }

    // 进页面即开服务：用户点进来就是为了传书，再让他按一次开关是多余的一步。
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LanTransferEvent.Message -> snackbar.showSnackbar(event.text)
                LanTransferEvent.Imported -> onImported()
            }
        }
    }

    MoReadBackdrop {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "局域网传书",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (state.running) "服务已开启" else "服务未开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { if (state.running) viewModel.stop() else viewModel.start() }) {
                        Text(if (state.running) "停止服务" else "开启服务")
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        AddressCard(
                            address = state.address,
                            error = state.error,
                            showQr = showQr,
                            onToggleQr = { showQr = !showQr },
                            onCopy = {
                                state.address?.let {
                                    clipboard.setText(AnnotatedString(it))
                                }
                            }
                        )
                    }

                    state.incoming?.let { incoming ->
                        item {
                            FrostedSurface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "正在接收 ${incoming.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (incoming.totalBytes > 0) {
                                        LinearProgressIndicator(
                                            progress = {
                                                (incoming.receivedBytes.toFloat() /
                                                    incoming.totalBytes).coerceIn(0f, 1f)
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "${formatSize(incoming.receivedBytes)} / " +
                                                formatSize(incoming.totalBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }

                    if (state.received.isNotEmpty()) {
                        item {
                            Text(
                                "已接收 ${state.received.size} 个文件",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(state.received, key = LanReceivedFile::path) { file ->
                            ReceivedFileRow(file = file, onDiscard = { viewModel.discard(file) })
                        }
                        item {
                            Button(
                                onClick = viewModel::importAll,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("全部导入书架（${state.received.size}）")
                            }
                        }
                    } else {
                        item { EmptyHint(running = state.running) }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun AddressCard(
    address: String?,
    error: String?,
    showQr: Boolean,
    onToggleQr: () -> Unit,
    onCopy: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 5.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "在同一 Wi-Fi 的电脑浏览器里打开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            if (address == null) {
                Text(
                    error ?: "服务未开启",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        address,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopy) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("复制", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = onToggleQr) {
                        Icon(
                            Icons.Outlined.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(if (showQr) "收起二维码" else "二维码", Modifier.padding(start = 6.dp))
                    }
                }
                if (showQr) {
                    QrCode(content = address, modifier = Modifier.size(200.dp))
                    Text(
                        "手机扫码只对另一台设备有用；电脑请直接输入上面的地址。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 用 zxing 只算出矩阵，落到 Compose Canvas 上自己画：省掉 Bitmap 分配与回收，
 * 也不必为一张二维码引入 zxing 的 Android 绑定库。
 */
@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surface
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                QR_MATRIX_SIZE,
                QR_MATRIX_SIZE,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8"
                )
            )
        }.getOrNull()
    } ?: return

    Canvas(modifier) {
        drawRect(background)
        val cell = minOf(size.width / matrix.width, size.height / matrix.height)
        val offsetX = (size.width - cell * matrix.width) / 2f
        val offsetY = (size.height - cell * matrix.height) / 2f
        for (row in 0 until matrix.height) {
            for (column in 0 until matrix.width) {
                if (!matrix.get(column, row)) continue
                drawRect(
                    color = foreground,
                    topLeft = Offset(offsetX + column * cell, offsetY + row * cell),
                    // 相邻模块之间多画 0.5px，避免缩放时出现摩尔纹般的细缝
                    size = Size(cell + 0.5f, cell + 0.5f)
                )
            }
        }
    }
}

@Composable
private fun ReceivedFileRow(file: LanReceivedFile, onDiscard: () -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatSize(file.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDiscard) {
                Icon(Icons.Outlined.Close, contentDescription = "丢弃这个文件")
            }
        }
    }
}

@Composable
private fun EmptyHint(running: Boolean) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                if (running) "等待电脑上传…" else "开启服务后，这里会列出收到的文件",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "支持 TXT 与 EPUB，单个文件上限 200 MB。文件先落在缓存里，" +
                    "确认无误再按「全部导入书架」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "这个服务没有密码：同一 Wi-Fi 下的任何人都能上传，用完请停止。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private const val QR_MATRIX_SIZE = 256
