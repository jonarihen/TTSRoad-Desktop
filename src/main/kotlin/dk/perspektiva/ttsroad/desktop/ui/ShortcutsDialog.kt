package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.desktop.nav.ShortcutHelpTable

/**
 * The in-app keyboard reference.
 *
 * Required by the issue in its own right — a shortcut nobody can discover is not a feature — and
 * it is also the only place the *alternatives* are written down, since the matcher accepts several
 * combinations per action.
 *
 * The table itself lives next to the matcher in `nav/Shortcuts.kt`, so adding a binding and
 * documenting it are one edit rather than two.
 */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            // Escape closes this before anything else acts on it, matching every other overlay.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .semantics { paneTitle = "Keyboard shortcuts" },
        containerColor = AarisColor.BgRaise,
        shape = RectangleShape,
        title = {
            Column {
                MetaText(text = "// Keyboard", color = AarisColor.Accent)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Shortcuts",
                    style = MaterialTheme.typography.titleLarge,
                    color = AarisColor.Ink,
                )
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetaText("Shortcuts that would type into a search box are ignored while you are typing there.")
                Spacer(Modifier.height(2.dp))
                ShortcutHelpTable.forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        MetaText(
                            text = row.keys,
                            color = AarisColor.Ink,
                            modifier = Modifier.width(190.dp),
                        )
                        Text(
                            row.action,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AarisColor.Muted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shape = RectangleShape) {
                Text("CLOSE", color = AarisColor.Ink)
            }
        },
    )
}
