package com.mozhi.reader.feature.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Summarize

internal fun companionComposerActions(
    isStreaming: Boolean,
    spoilerProtectionEnabled: Boolean,
    multiBubbleEnabled: Boolean,
    onPickImage: () -> Unit,
    onPickTextFile: () -> Unit,
    onGeneratePlotSummary: () -> Unit,
    onGenerateIllustration: () -> Unit,
    onToggleSpoilerProtection: () -> Unit,
    onToggleMultiBubble: () -> Unit
): List<ComposerAction> = listOf(
    ComposerAction(
        icon = Icons.Outlined.Image,
        label = "图片",
        onClick = onPickImage
    ),
    ComposerAction(
        icon = Icons.Outlined.Description,
        label = "文本文件",
        onClick = onPickTextFile
    ),
    ComposerAction(
        icon = Icons.Outlined.Summarize,
        label = "剧情梗概",
        enabled = !isStreaming,
        onClick = onGeneratePlotSummary
    ),
    ComposerAction(
        icon = Icons.Outlined.Brush,
        label = "生成插图",
        enabled = !isStreaming,
        onClick = onGenerateIllustration
    ),
    ComposerAction(
        icon = Icons.Outlined.Shield,
        label = "防剧透",
        selected = spoilerProtectionEnabled,
        onClick = onToggleSpoilerProtection
    ),
    ComposerAction(
        icon = Icons.Outlined.ChatBubbleOutline,
        label = "多气泡",
        selected = multiBubbleEnabled,
        onClick = onToggleMultiBubble
    )
)
