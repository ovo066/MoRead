package com.mozhi.reader.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.MoReadTokens

@Composable
internal fun MoReadSearchCapsule(query: String, onQueryChange: (String) -> Unit, floating: Boolean = false,
    placeholder: String = "搜索书名、作者或想法", testTagPrefix: String = "search") {
    val textStyle = MaterialTheme.typography.bodyMedium
    val elevation by animateDpAsState(if (floating) 8.dp else 3.dp, label = "search capsule lift")
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().testTag("$testTagPrefix-search-capsule"),
        shape = MoReadTokens.CapsuleShape,
        color = if (floating) MaterialTheme.colorScheme.surface.copy(alpha = .96f) else Color.Unspecified,
        shadowElevation = elevation
    ) {
        Row(
            modifier = Modifier
                // Keep book lettering from competing with the query while the capsule floats.
                .background(if (floating) MaterialTheme.colorScheme.surface.copy(alpha = .94f) else Color.Transparent)
                .heightIn(min = 46.dp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("$testTagPrefix-search")
                )
            }
        }
    }
}

