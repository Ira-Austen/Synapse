package com.synapse.app.ui

import android.os.Build
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.synapse.app.data.AppInspector
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SynapseColors {
    val Emerald = Color(0xFF10B981)
    val EmeraldContainer = Color(0xFFD1FAE5)
    val EmeraldOnContainer = Color(0xFF065F46)

    val Amber = Color(0xFFF59E0B)
    val AmberContainer = Color(0xFFFEF3C7)
    val AmberOnContainer = Color(0xFF92400E)

    val Rose = Color(0xFFEF4444)
    val RoseContainer = Color(0xFFFEE2E2)
    val RoseOnContainer = Color(0xFF991B1B)

    val Sky = Color(0xFF0284C7)
    val SkyContainer = Color(0xFFE0F2FE)
    val SkyOnContainer = Color(0xFF075985)
}

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF93C5FD),
    background = Color(0xFF0B1120),
    surface = Color(0xFF131C2E),
    surfaceContainerLow = Color(0xFF0F172A),
    surfaceVariant = Color(0xFF1E293B)
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF1D4ED8),
    background = Color(0xFFF3F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F6FA),
    surfaceVariant = Color(0xFFE9EFF6)
)

@Composable
fun SynapseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val base = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme -> dynamicLightColorScheme(ctx)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    // Give light/dark canvas a crisp contrast against elevated surface cards
    val polished = if (darkTheme) {
        base.copy(
            background = base.surfaceContainerLowest,
            surface = base.surfaceContainerLow
        )
    } else {
        base.copy(
            background = base.surfaceContainerLow,
            surface = base.surfaceContainerLowest
        )
    }
    MaterialTheme(colorScheme = polished, content = content)
}

@Composable
fun SynapseCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    cornerRadius: Dp = 20.dp,
    borderAlpha: Float = 0.35f,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (borderAlpha > 0f) {
            BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha))
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    dotColor: Color? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
fun AnimatedCountTile(
    count: Int,
    label: String,
    suffix: String = "",
    modifier: Modifier = Modifier
) {
    val animated by animateIntAsState(
        targetValue = count,
        animationSpec = tween(durationMillis = 260),
        label = "metric_$label"
    )
    MetricTile(
        value = "$animated$suffix",
        label = label,
        modifier = modifier
    )
}

@Composable
fun SectionHeader(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun formatTimeHms(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--:--:--"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}

fun formatDateTimeShort(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--"
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}

val LocalNowMs = compositionLocalOf { System.currentTimeMillis() }

fun formatFriendlyTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs <= 0L) return "--"
    val diffMs = nowMs - timestampMs
    if (diffMs < 0L) {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }
    if (diffMs < 10_000L) return "刚刚"
    if (diffMs < 60_000L) return "${diffMs / 1_000L} 秒前"
    if (diffMs < 3_600_000L) return "${diffMs / 60_000L} 分钟前"

    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val tsCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameYear = nowCal.get(Calendar.YEAR) == tsCal.get(Calendar.YEAR)
    val nowDay = nowCal.get(Calendar.DAY_OF_YEAR)
    val tsDay = tsCal.get(Calendar.DAY_OF_YEAR)

    return when {
        sameYear && nowDay == tsDay ->
            SimpleDateFormat("今天 HH:mm", Locale.getDefault()).format(Date(timestampMs))
        sameYear && nowDay - tsDay == 1 ->
            SimpleDateFormat("昨天 HH:mm", Locale.getDefault()).format(Date(timestampMs))
        sameYear ->
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))
        else ->
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }
}

@Composable
fun FriendlyTimeText(
    timestampMs: Long,
    modifier: Modifier = Modifier
) {
    val nowMs = LocalNowMs.current
    Text(
        text = formatFriendlyTime(timestampMs, nowMs),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun AppIconAvatar(
    pkg: String,
    label: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(pkg) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(pkg) {
        bitmap = withContext(Dispatchers.IO) {
            AppInspector.getAppIcon(context, pkg)
        }
    }

    val shape = RoundedCornerShape(size * 0.28f)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = label,
            modifier = modifier
                .size(size)
                .clip(shape)
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = shape,
            modifier = modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = label.trim().firstOrNull()?.uppercase() ?: "A",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
