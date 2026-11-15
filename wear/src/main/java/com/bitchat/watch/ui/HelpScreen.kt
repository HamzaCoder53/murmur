package com.bitchat.watch.ui

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

/** One card of the "How it works" guide. */
internal data class HelpTopic(val title: String, val body: String)

/** The watch's built-in guide, short enough to read on a wrist. */
internal object HelpContent {
    val topics = listOf(
        HelpTopic(
            "Chat nearby",
            "No account or phone number. Messages go phone to phone and watch to watch over " +
                "Bluetooth, hopping through up to 7 devices. Works with Bitchat users too."
        ),
        HelpTopic(
            "Public and private",
            "The main chat is public to everyone nearby. Tap a name to message privately: " +
                "private chats are encrypted end to end."
        ),
        HelpTopic(
            "With your phone",
            "With Skein open on your phone, the watch turns its own radio off and sends " +
                "through the phone. People → Link shows Phone."
        ),
        HelpTopic(
            "Link to phone",
            "Menu → Link to phone gives the watch your phone's identity: one inbox on both, " +
                "same name, photos and read status in sync. Confirm on the phone."
        ),
        HelpTopic(
            "On its own",
            "Away from the phone the watch uses its own Bluetooth, scanning briefly each " +
                "minute to save battery. People → Link shows Own radio."
        ),
        HelpTopic(
            "Favorites",
            "In a chat, tap the name at the top, then Favorite. When you both favorite each " +
                "other (Mutual) you can message over the internet when far apart."
        ),
        HelpTopic(
            "Internet messages",
            "For mutual favorites out of Bluetooth range, messages go over the internet " +
                "through public Nostr relays. They are encrypted: relays can't read them."
        ),
        HelpTopic(
            "How often",
            "Menu → Internet DMs: Off, every 5 min, every 1 min or Always on. Always on is " +
                "fastest and uses the most battery. Check now fetches at once."
        ),
        HelpTopic(
            "Location chat",
            "Menu → Location chat: public chat with people in your block, neighborhood or city, " +
                "over the internet. Only the approximate area is shared."
        ),
        HelpTopic(
            "Tile",
            "Swipe left on the watch face, tap +, choose Skein: new messages, people nearby " +
                "and Phone or Own radio at a glance."
        ),
        HelpTopic(
            "Complication",
            "Long-press the watch face → Edit → Complications → Skein messages for an unread " +
                "count on your watch face."
        ),
        HelpTopic(
            "Privacy",
            "Nothing is stored on a server. Menu → Save history keeps private chats on this " +
                "watch only; turn it off to erase them."
        )
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
                        text = "How it works",
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
                        text = topic.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = topic.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
