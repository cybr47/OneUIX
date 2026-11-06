package io.github.soclear.oneuix.ui.category

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.soclear.oneuix.R

/**
 * Initial settings destination for the OneUIX XMorphClock integration.
 *
 * This is intentionally informational until the Samsung lock-screen adapter and
 * the reusable clock renderer have been ported and validated.
 */
@Composable
fun DetailPaneXMorphClock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(30.dp))
        Text(
            text = stringResource(R.string.xmorph_clock),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.xmc_page_intro),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.xmc_stage_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.xmc_stage_summary),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(20.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.xmc_renderer_title)) },
            supportingContent = { Text(stringResource(R.string.xmc_renderer_summary)) }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.xmc_lockscreen_title)) },
            supportingContent = { Text(stringResource(R.string.xmc_lockscreen_summary)) }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.xmc_aod_title)) },
            supportingContent = { Text(stringResource(R.string.xmc_aod_summary)) }
        )
    }
}
