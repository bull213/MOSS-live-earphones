package com.visualassistant.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.visualassistant.app.R

/** The camera content is only composed after the user chooses to get help. */
@Composable
internal fun AssistantNavigation(camera: @Composable (onFinish: () -> Unit) -> Unit) {
    var showCamera by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showCamera) { showCamera = false }
    if (showCamera) {
        camera { showCamera = false }
    } else {
        HomeScreen(onGetHelp = { showCamera = true })
    }
}

@Composable
internal fun HomeScreen(onGetHelp: () -> Unit) {
    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.app_name).uppercase(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CameraHero()
            LargeButton(stringResource(R.string.home_get_help), onClick = onGetHelp)
            Text(stringResource(R.string.home_examples_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_examples), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CameraHero() {
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.fillMaxWidth().height(128.dp).background(Color.Transparent)) {
                val center = Offset(size.width * .5f, size.height * .5f)
                val scale = size.height
                drawCircle(ink.copy(alpha = .08f), radius = scale * .49f, center = center)
                drawRoundRect(ink.copy(alpha = .16f), topLeft = Offset(center.x - scale * .43f, center.y - scale * .29f),
                    size = Size(scale * .86f, scale * .58f), cornerRadius = CornerRadius(scale * .1f))
                drawCircle(ink, radius = scale * .21f, center = center, style = Stroke(width = scale * .045f))
                drawCircle(ink.copy(alpha = .65f), radius = scale * .08f, center = center)
                drawCircle(ink, radius = scale * .035f, center = Offset(center.x + scale * .31f, center.y - scale * .18f))
            }
            Text(stringResource(R.string.home_hero_title), color = ink, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_camera_source), color = ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
