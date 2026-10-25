package io.github.soclear.oneuix.ui.category

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.soclear.oneuix.R
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.ui.FodIconStore
import io.github.soclear.oneuix.ui.component.SwitchItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun FodItem(config: FodConfig, onEvent: (SystemUIEvent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var glassPercent by remember(config.glassSizePercent) { mutableFloatStateOf(config.glassScale * 100f) }
    var percent by remember(config.sizePercent) { mutableFloatStateOf(config.scale * 100f) }
    val preview by produceState<Bitmap?>(null, config.iconSlot, config.iconDigest) {
        value = withContext(Dispatchers.IO) { FodIconStore.preview(config) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            failed = false
            scope.launch {
                try {
                    val selected = withContext(Dispatchers.IO) { FodIconStore.importIcon(context, uri, config) }
                    onEvent(SystemUIEvent.Fod.SelectIcon(selected.iconSlot, selected.iconDigest))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }
    Column {
        SwitchItem(
            title = stringResource(R.string.fod_title),
            summary = stringResource(R.string.fod_summary),
            icon = ImageVector.vectorResource(R.drawable.fingerprint),
            clickable = true,
            onClick = { if (config.hideIcon) expanded = !expanded },
            checked = config.hideIcon,
            onCheckedChange = {
                expanded = it
                onEvent(SystemUIEvent.Fod.Enabled(it))
            },
        )
        AnimatedVisibility(expanded && config.hideIcon) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.fod_replace), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.fod_png_summary), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { picker.launch(arrayOf("image/png")) }) {
                        Text(stringResource(if (busy) R.string.fod_importing else R.string.fod_choose))
                    }
                    if (config.hasCustomIcon) TextButton(enabled = !busy, onClick = {
                        failed = false
                        onEvent(SystemUIEvent.Fod.RemoveIcon)
                    }) { Text(stringResource(R.string.fod_remove)) }
                }
                if (failed) Text(stringResource(R.string.fod_import_failed), color = MaterialTheme.colorScheme.error)
                preview?.let { bitmap ->
                    // Neutral backgrounds make both light and dark transparent artwork visible.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Color(0xFF202124), Color(0xFFE8EAED)).forEach { background ->
                            Box(Modifier.size(96.dp).background(background, MaterialTheme.shapes.medium),
                                contentAlignment = Alignment.Center) {
                                Image(bitmap.asImageBitmap(), stringResource(R.string.fod_preview),
                                    Modifier.size((48f * percent / 100f).dp), contentScale = ContentScale.Fit)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.fod_size, percent.roundToInt()))
                Slider(value = percent, onValueChange = { percent = it }, valueRange = 50f..150f,
                    steps = 99, enabled = config.hasCustomIcon && !busy,
                    onValueChangeFinished = { onEvent(SystemUIEvent.Fod.Size(percent.roundToInt())) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50%")
                    TextButton(enabled = config.hasCustomIcon && !busy, onClick = {
                        percent = 100f
                        onEvent(SystemUIEvent.Fod.Size(100))
                    }) { Text(stringResource(R.string.fod_reset_size)) }
                    Text("150%")
                }
                Text(stringResource(R.string.fod_scope), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp))
            }
        }
        SwitchItem(
            title = stringResource(R.string.fod_glass_title),
            summary = stringResource(R.string.fod_glass_summary),
            icon = ImageVector.vectorResource(R.drawable.fingerprint),
            checked = config.glassBackground,
            onCheckedChange = { onEvent(SystemUIEvent.Fod.Glass(it)) },
        )
        AnimatedVisibility(config.glassBackground) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.fod_glass_size, glassPercent.roundToInt()))
                Slider(value = glassPercent, onValueChange = { glassPercent = it }, valueRange = 50f..150f,
                    steps = 99,
                    onValueChangeFinished = { onEvent(SystemUIEvent.Fod.GlassSize(glassPercent.roundToInt())) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50%")
                    TextButton(onClick = {
                        glassPercent = 100f
                        onEvent(SystemUIEvent.Fod.GlassSize(100))
                    }) { Text(stringResource(R.string.fod_reset_size)) }
                    Text("150%")
                }
            }
        }
    }
}
