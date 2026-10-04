package com.mozhi.reader.feature.review

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Device tilt for the 3D review cards. Read only inside draw/layer blocks so sensor ticks never recompose. */
internal class ReviewTiltState {
    var x by mutableFloatStateOf(0f)
        private set
    var y by mutableFloatStateOf(0f)
        private set
    private var restingY = Float.NaN

    fun update(gravityX: Float, gravityY: Float) {
        if (restingY.isNaN()) restingY = gravityY
        val (targetX, targetY) = reviewTiltDegrees(gravityX, gravityY, restingY)
        // Low-pass the raw reading; it also lets the resting posture drift with the reader's hands.
        x += (targetX - x) * .18f
        y += (targetY - y) * .18f
        restingY += (gravityY - restingY) * .004f
    }

    fun reset() { x = 0f; y = 0f; restingY = Float.NaN }
}

@Composable
internal fun rememberReviewTilt(enabled: Boolean): ReviewTiltState {
    val context = LocalContext.current
    val state = remember { ReviewTiltState() }
    DisposableEffect(enabled, context) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { if (event.values.size >= 2) state.update(event.values[0], event.values[1]) }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = enabled && manager != null && sensor != null &&
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            if (registered) manager?.unregisterListener(listener)
            state.reset()
        }
    }
    return state
}
