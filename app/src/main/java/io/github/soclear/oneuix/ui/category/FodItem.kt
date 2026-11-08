package io.github.soclear.oneuix.ui.category

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import io.github.soclear.oneuix.R
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.ui.component.SwitchItem

@Composable
fun FodItem(config: FodConfig, onEvent: (SystemUIEvent) -> Unit) {
    SwitchItem(
        title = stringResource(R.string.fod_title),
        summary = stringResource(R.string.fod_summary),
        icon = ImageVector.vectorResource(R.drawable.fingerprint),
        checked = config.hideIcon,
        onCheckedChange = { onEvent(SystemUIEvent.Fod.Enabled(it)) },
    )
}
