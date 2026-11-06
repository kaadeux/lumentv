package com.lumentv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Two icons the core Material set lacks: a ball for Sports and a record dot for Recordings. */
object LumenIcons {
    val Sports: ImageVector = ImageVector.Builder("sports", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 2f); arcToRelative(10f, 10f, 0f, true, true, 0f, 20f); arcToRelative(10f, 10f, 0f, true, true, 0f, -20f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 7f); lineTo(16.3f, 10.1f); lineTo(14.6f, 15.1f); lineTo(9.4f, 15.1f); lineTo(7.7f, 10.1f); close()
        }
    }.build()

    val Download: ImageVector = ImageVector.Builder("download", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 3f); lineTo(12f, 15f)
            moveTo(6.5f, 10f); lineTo(12f, 15.5f); lineTo(17.5f, 10f)
            moveTo(4f, 20f); lineTo(20f, 20f)
        }
    }.build()
}
