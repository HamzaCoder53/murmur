package com.bitchat.watch.ui

import androidx.annotation.StringRes
import com.bitchat.watch.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** One card of the "How it works" guide, as string resources so it follows the watch's language. */
internal data class HelpTopic(@StringRes val title: Int, @StringRes val body: Int)

/** The watch's built-in guide, short enough to read on a wrist. */
internal object HelpContent {
    val topics = listOf(
        HelpTopic(R.string.help_nearby_title, R.string.help_nearby_body),
        HelpTopic(R.string.help_public_title, R.string.help_public_body),
        HelpTopic(R.string.help_phone_title, R.string.help_phone_body),
        HelpTopic(R.string.help_link_title, R.string.help_link_body),
        HelpTopic(R.string.help_alone_title, R.string.help_alone_body),
        HelpTopic(R.string.help_favorites_title, R.string.help_favorites_body),
        HelpTopic(R.string.help_internet_title, R.string.help_internet_body),
        HelpTopic(R.string.help_frequency_title, R.string.help_frequency_body),
        HelpTopic(R.string.help_location_title, R.string.help_location_body),
        HelpTopic(R.string.help_tile_title, R.string.help_tile_body),
        HelpTopic(R.string.help_complication_title, R.string.help_complication_body),
        HelpTopic(R.string.help_privacy_title, R.string.help_privacy_body),
        HelpTopic(R.string.help_language_title, R.string.help_language_body),
    )
}

@Composable
fun HelpScreen() {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { scaffoldPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = scaffoldPadding,
        ) {
            item {
                ListHeader {
                    Text(
                        text = stringResource(R.string.help_title),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            items(HelpContent.topics) { topic ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(topic.title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(topic.body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
