package dk.perspektiva.ttsroad.desktop.data

/**
 * Content plus what is currently true about it.
 *
 * The four fields are independent on purpose, because the states they combine into are what the
 * previous `Load.Loading | Ok | Err` could not express:
 *
 * - [value] + [isRefreshing] — content on screen while a refresh runs underneath it.
 * - [value] + [error] — content on screen that the last refresh **failed** to update. The value is
 *   never dropped, and the UI must not present it as current: that is what [lastSuccessMillis] is
 *   for.
 * - [error] with no [value] — the only failure that owes a full-screen error and a Retry.
 *
 * `null` for [lastSuccessMillis] means "never loaded", not "loaded at the epoch".
 */
data class Cached<T>(
    val value: T? = null,
    val error: String? = null,
    val isRefreshing: Boolean = false,
    val lastSuccessMillis: Long? = null,
) {
    val hasContent: Boolean get() = value != null

    /** True only while there is nothing at all to show yet — the one case that owes a spinner. */
    val isInitialLoad: Boolean get() = value == null && error == null

    /** Content is on screen but the newest attempt to update it failed. */
    val isStale: Boolean get() = value != null && error != null
}
