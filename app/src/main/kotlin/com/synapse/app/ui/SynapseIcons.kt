package com.synapse.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight, zero-dependency Material 3 style vector icons (24x24 viewport).
 */
object SynapseIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    val Shield: ImageVector by lazy {
        icon("Shield") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(4f, 5f)
                verticalLineTo(11.09f)
                curveTo(4f, 16.14f, 7.41f, 20.85f, 12f, 22f)
                curveTo(16.59f, 20.85f, 20f, 16.14f, 20f, 11.09f)
                verticalLineTo(5f)
                lineTo(12f, 2f)
                close()
                moveTo(10.94f, 15.54f)
                lineTo(7.4f, 12f)
                lineTo(8.81f, 10.59f)
                lineTo(10.94f, 12.72f)
                lineTo(15.18f, 8.48f)
                lineTo(16.59f, 9.89f)
                lineTo(10.94f, 15.54f)
                close()
            }
        }
    }

    val Apps: ImageVector by lazy {
        icon("Apps") {
            path(fill = SolidColor(Color.Black)) {
                // 2x2 rounded grid
                moveTo(4f, 4f); horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(4f); close()
                moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f); horizontalLineTo(14f); close()
                moveTo(4f, 14f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(14f, 14f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(14f); close()
            }
        }
    }

    val Processes: ImageVector by lazy {
        icon("Processes") {
            path(fill = SolidColor(Color.Black)) {
                // Stacked layers / activity monitor
                moveTo(11.99f, 18.54f)
                lineTo(4.62f, 12.81f)
                lineTo(3f, 14.07f)
                lineTo(12f, 21.07f)
                lineTo(21f, 14.07f)
                lineTo(19.37f, 12.8f)
                lineTo(11.99f, 18.54f)
                close()
                moveTo(12f, 16f)
                lineTo(19.36f, 10.27f)
                lineTo(21f, 9f)
                lineTo(12f, 2f)
                lineTo(3f, 9f)
                lineTo(4.63f, 10.27f)
                lineTo(12f, 16f)
                close()
            }
        }
    }

    val Notifications: ImageVector by lazy {
        icon("Notifications") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 22f)
                curveTo(13.1f, 22f, 14f, 21.1f, 14f, 20f)
                horizontalLineTo(10f)
                curveTo(10f, 21.1f, 10.9f, 22f, 12f, 22f)
                close()
                moveTo(18f, 16f)
                verticalLineTo(11f)
                curveTo(18f, 7.93f, 16.37f, 5.36f, 13.5f, 4.68f)
                verticalLineTo(4f)
                curveTo(13.5f, 3.17f, 12.83f, 2.5f, 12f, 2.5f)
                curveTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4f)
                verticalLineTo(4.68f)
                curveTo(7.64f, 5.36f, 6f, 7.92f, 6f, 11f)
                verticalLineTo(16f)
                lineTo(4f, 18f)
                verticalLineTo(19f)
                horizontalLineTo(20f)
                verticalLineTo(18f)
                lineTo(18f, 16f)
                close()
            }
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 17f); verticalLineTo(19f); horizontalLineTo(9f); verticalLineTo(17f); horizontalLineTo(3f); close()
                moveTo(3f, 5f); verticalLineTo(7f); horizontalLineTo(13f); verticalLineTo(5f); horizontalLineTo(3f); close()
                moveTo(13f, 21f); verticalLineTo(19f); horizontalLineTo(21f); verticalLineTo(17f); horizontalLineTo(13f); verticalLineTo(15f); horizontalLineTo(11f); verticalLineTo(21f); horizontalLineTo(13f); close()
                moveTo(7f, 9f); verticalLineTo(11f); horizontalLineTo(3f); verticalLineTo(13f); horizontalLineTo(7f); verticalLineTo(15f); horizontalLineTo(9f); verticalLineTo(9f); horizontalLineTo(7f); close()
                moveTo(21f, 13f); verticalLineTo(11f); horizontalLineTo(11f); verticalLineTo(13f); horizontalLineTo(21f); close()
                moveTo(15f, 9f); horizontalLineTo(17f); verticalLineTo(7f); horizontalLineTo(21f); verticalLineTo(5f); horizontalLineTo(17f); verticalLineTo(3f); horizontalLineTo(15f); verticalLineTo(9f); close()
            }
        }
    }

    val Sparkle: ImageVector by lazy {
        icon("Sparkle") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(14.5f, 9.5f)
                lineTo(22f, 12f)
                lineTo(14.5f, 14.5f)
                lineTo(12f, 22f)
                lineTo(9.5f, 14.5f)
                lineTo(2f, 12f)
                lineTo(9.5f, 9.5f)
                close()
            }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                curveTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f)
                curveTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f)
                curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f)
                horizontalLineTo(17.65f)
                curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
                curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
                curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
                curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
                lineTo(13f, 11f)
                horizontalLineTo(20f)
                verticalLineTo(4f)
                lineTo(17.65f, 6.35f)
                close()
            }
        }
    }

    val Add: ImageVector by lazy {
        icon("Add") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 13f); horizontalLineTo(13f); verticalLineTo(19f); horizontalLineTo(11f)
                verticalLineTo(13f); horizontalLineTo(5f); verticalLineTo(11f); horizontalLineTo(11f)
                verticalLineTo(5f); horizontalLineTo(13f); verticalLineTo(11f); horizontalLineTo(19f); close()
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(15.5f, 14f)
                horizontalLineTo(14.71f)
                lineTo(14.43f, 13.73f)
                curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
                curveTo(5.91f, 3f, 3f, 5.91f, 3f, 9.5f)
                curveTo(3f, 13.09f, 5.91f, 16f, 9.5f, 16f)
                curveTo(11.11f, 16f, 12.59f, 15.41f, 13.73f, 14.43f)
                lineTo(14f, 14.71f)
                verticalLineTo(15.5f)
                lineTo(19f, 20.49f)
                lineTo(20.49f, 19f)
                lineTo(15.5f, 14f)
                close()
                moveTo(9.5f, 14f)
                curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f)
                curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
                curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f)
                close()
            }
        }
    }

    val PlayArrow: ImageVector by lazy {
        icon("PlayArrow") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 5f); verticalLineTo(19f); lineTo(19f, 12f); close()
            }
        }
    }

    val ExpandMore: ImageVector by lazy {
        icon("ExpandMore") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
            }
        }
    }

    val ExpandLess: ImageVector by lazy {
        icon("ExpandLess") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f)
            }
        }
    }

    val Delete: ImageVector by lazy {
        icon("Delete") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f)
                curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
                horizontalLineTo(16f)
                curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
                verticalLineTo(7f)
                horizontalLineTo(6f)
                verticalLineTo(19f)
                close()
                moveTo(19f, 4f)
                horizontalLineTo(15.5f)
                lineTo(14.5f, 3f)
                horizontalLineTo(9.5f)
                lineTo(8.5f, 4f)
                horizontalLineTo(5f)
                verticalLineTo(6f)
                horizontalLineTo(19f)
                verticalLineTo(4f)
                close()
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }
}
