package io.github.soclear.oneuix.ui.category

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import io.github.soclear.oneuix.R
import io.github.soclear.oneuix.common.Preference
import io.github.soclear.oneuix.ui.SettingViewModel
import io.github.soclear.oneuix.ui.component.SwitchItem
import kotlin.math.roundToInt

@Composable
fun DetailPaneLauncher(
    uiState: Preference.Other,
    onEvent: (LauncherEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    PackagePane(modifier) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            SwitchItem(
                icon = ImageVector.vectorResource(id = R.drawable.memory),
                title = stringResource(id = R.string.showMemoryUsageInRecents_title),
                checked = uiState.showMemoryUsageInRecents,
                onCheckedChange = { onEvent(LauncherEvent.ShowMemoryUsageInRecents(it)) }
            )
        }
        SwitchItem(
            icon = ImageVector.vectorResource(id = R.drawable.tile_medium),
            title = stringResource(id = R.string.recentsGridThreeRows_title),
            summary = stringResource(id = R.string.recentsGridThreeRows_summary),
            checked = uiState.recentsGridThreeRows,
            onCheckedChange = { onEvent(LauncherEvent.RecentsGridThreeRows(it)) }
        )
        SwitchItem(
            icon = ImageVector.vectorResource(id = R.drawable.block),
            title = stringResource(id = R.string.hideRecentsCloseAllButton_title),
            checked = uiState.hideRecentsCloseAllButton,
            onCheckedChange = { onEvent(LauncherEvent.HideRecentsCloseAllButton(it)) }
        )
        SwitchItem(
            icon = ImageVector.vectorResource(id = R.drawable.apps),
            title = stringResource(id = R.string.hideAppsSearchBar_title),
            checked = uiState.hideAppsSearchBar,
            onCheckedChange = { onEvent(LauncherEvent.HideAppsSearchBar(it)) }
        )
        SwitchItem(
            icon = ImageVector.vectorResource(id = R.drawable.apps),
            title = stringResource(id = R.string.searchBarColors_title),
            summary = stringResource(id = R.string.searchBarColors_summary),
            checked = uiState.searchBarColorsEnabled,
            onCheckedChange = { onEvent(LauncherEvent.SearchBarColorsEnabled(it)) }
        )
        AnimatedVisibility(uiState.searchBarColorsEnabled) {
            // Keep the value locally while dragging and save once on release, like the other sliders.
            var color by remember { mutableFloatStateOf(uiState.searchBarColorLevel.coerceIn(0f, 1f)) }
            var transparency by remember {
                mutableFloatStateOf(uiState.searchBarBackgroundTransparency.coerceIn(0f, 1f))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(
                        id = R.string.searchBarColor_value,
                        stringResource(id = R.string.searchBarColor_title),
                        (color * 100).roundToInt()
                    )
                )
                Slider(
                    value = color,
                    onValueChange = { color = it },
                    valueRange = 0f..1f,
                    onValueChangeFinished = { onEvent(LauncherEvent.SearchBarColorLevel(color)) }
                )
                Text(
                    stringResource(
                        id = R.string.searchBarColor_value,
                        stringResource(id = R.string.searchBarTransparency_title),
                        (transparency * 100).roundToInt()
                    )
                )
                Slider(
                    value = transparency,
                    onValueChange = { transparency = it },
                    valueRange = 0f..1f,
                    onValueChangeFinished = { onEvent(LauncherEvent.SearchBarTransparency(transparency)) }
                )
            }
        }
        SwitchItem(
            icon = ImageVector.vectorResource(id = R.drawable.position_bottom_right),
            title = stringResource(id = R.string.removeShortcutBadge_title),
            summary = stringResource(id = R.string.removeShortcutBadge_summary),
            checked = uiState.removeShortcutBadge,
            onCheckedChange = { onEvent(LauncherEvent.RemoveShortcutBadge(it)) }
        )
    }
}

sealed interface LauncherEvent {
    @JvmInline
    value class ShowMemoryUsageInRecents(val value: Boolean) : LauncherEvent

    @JvmInline
    value class RecentsGridThreeRows(val value: Boolean) : LauncherEvent

    @JvmInline
    value class HideRecentsCloseAllButton(val value: Boolean) : LauncherEvent

    @JvmInline
    value class HideAppsSearchBar(val value: Boolean) : LauncherEvent

    @JvmInline
    value class SearchBarColorsEnabled(val value: Boolean) : LauncherEvent

    @JvmInline
    value class SearchBarColorLevel(val value: Float) : LauncherEvent

    @JvmInline
    value class SearchBarTransparency(val value: Float) : LauncherEvent

    @JvmInline
    value class RemoveShortcutBadge(val value: Boolean) : LauncherEvent
}

fun SettingViewModel.onLauncherEvent(event: LauncherEvent) {
    updateData { preference ->
        when (event) {
            is LauncherEvent.ShowMemoryUsageInRecents -> preference.copy(
                other = preference.other.copy(
                    showMemoryUsageInRecents = event.value
                )
            )

            is LauncherEvent.RecentsGridThreeRows -> preference.copy(
                other = preference.other.copy(
                    recentsGridThreeRows = event.value
                )
            )

            is LauncherEvent.HideRecentsCloseAllButton -> preference.copy(
                other = preference.other.copy(
                    hideRecentsCloseAllButton = event.value
                )
            )

            is LauncherEvent.HideAppsSearchBar -> preference.copy(
                other = preference.other.copy(
                    hideAppsSearchBar = event.value
                )
            )

            is LauncherEvent.SearchBarColorsEnabled -> preference.copy(other = preference.other.copy(searchBarColorsEnabled = event.value))
            is LauncherEvent.SearchBarColorLevel -> preference.copy(other = preference.other.copy(searchBarColorLevel = event.value))
            is LauncherEvent.SearchBarTransparency -> preference.copy(other = preference.other.copy(searchBarBackgroundTransparency = event.value))
            is LauncherEvent.RemoveShortcutBadge -> preference.copy(
                other = preference.other.copy(
                    removeShortcutBadge = event.value
                )
            )
        }
    }
}
