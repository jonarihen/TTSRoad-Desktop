package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.data.ChapterNotification
import dk.perspektiva.ttsroad.desktop.data.ChapterNotificationState
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.detailLabel

const val NotificationsListTestTag: String = "notificationsList"
const val NotificationRowTestTag: String = "notificationRow"
const val NotificationDismissTestTag: String = "notificationDismiss"
const val NotificationPlayTestTag: String = "notificationPlay"
const val ClearReadNotificationsTestTag: String = "clearReadNotifications"

/**
 * New chapters on the serials this account follows, from pulled to playable.
 *
 * The screen draws two things that look similar and mean opposite things — a chapter that is coming
 * and a chapter that has arrived — so the state is spelled out on every row rather than left to a
 * colour. A converting row deliberately offers **no** dismiss: the server refuses it with a 409,
 * and drawing a control that cannot succeed is worse than drawing none.
 */
@Composable
fun NotificationsScreen(
    holder: ChapterNotificationsStateHolder,
    repository: TtsRoadRepository,
    onPlay: (ChapterNotification) -> Unit,
    onOpenFiction: (ChapterNotification) -> Unit,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(holder) { holder.start() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize()
                .testTag(NotificationsListTestTag),
            contentPadding = PaddingValues(PageGutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Column {
                    SectionTitle("08", "New chapters")
                    Spacer(Modifier.height(10.dp))
                    MetaText(
                        "A chapter stays here from the moment it is pulled until it can be played.",
                        color = AarisColor.Dim,
                    )
                    if (state.hasClearable) {
                        Spacer(Modifier.height(14.dp))
                        // Says "ready", not "all". The converting rows are the ones being waited
                        // for, and a bulk action that swallowed them would make this untrustworthy
                        // exactly once.
                        AarisSecondaryAction(
                            label = "Clear the ${state.ready} ready",
                            onClick = holder::dismissRead,
                            modifier = Modifier.testTag(ClearReadNotificationsTestTag),
                        )
                    }
                    state.error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            val rows = ChapterNotificationsStateHolder.visible(state.notifications)
            when {
                state.unsupported -> item(key = "unsupported") {
                    EmptyState(
                        "This server cannot report new chapters",
                        "Following still works; there is just nothing to be told with.",
                    )
                }

                !state.loaded && state.loading -> item(key = "loading") { CenterProgress() }

                rows.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        "Nothing new",
                        "Follow a serial and its next chapter will appear here while it converts.",
                    )
                }

                else -> items(rows, key = { it.id }) { notification ->
                    NotificationRow(
                        notification = notification,
                        repository = repository,
                        onPlay = { onPlay(notification) },
                        onOpen = { onOpenFiction(notification) },
                        onDismiss = { holder.dismiss(notification) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: ChapterNotification,
    repository: TtsRoadRepository,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AarisCard(modifier = Modifier.fillMaxWidth().testTag(NotificationRowTestTag), onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CoverImage(
                notification.fiction.title,
                notification.fiction.coverImageUrl?.let(repository::resolveUrl),
                Modifier.width(44.dp).aspectRatio(2f / 3f),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    notification.fiction.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AarisColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaText(
                    notification.detailLabel(),
                    color = when (notification.presentation) {
                        ChapterNotificationState.Ready -> AarisColor.Accent
                        ChapterNotificationState.Stalled -> AarisColor.Warning
                        else -> AarisColor.Muted
                    },
                )
            }
            // Only a chapter that actually has audio offers Play, and only the server's own
            // `dismissible` offers Dismiss. Neither is inferred from the state name.
            if (notification.playable) {
                AarisSecondaryAction(
                    label = "Play",
                    onClick = onPlay,
                    modifier = Modifier.testTag(NotificationPlayTestTag),
                )
            }
            if (notification.dismissible) {
                AarisSecondaryAction(
                    label = "Dismiss",
                    onClick = onDismiss,
                    modifier = Modifier.testTag(NotificationDismissTestTag),
                )
            }
        }
    }
}
