package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json
import java.math.BigDecimal
import kotlin.math.ceil

data class FictionNotificationSettings(
    val mode: String,
    @param:Json(name = "backlog_hours") val backlogHours: Double,
    @param:Json(name = "remaining_seconds") val remainingSeconds: Double,
    @param:Json(name = "backlog_armed") val backlogArmed: Boolean? = null,
)

data class FictionNotificationSettingsRequest(
    val mode: String,
    @param:Json(name = "backlog_hours") val backlogHours: Double,
) {
    init {
        require(mode in FictionNotificationModes)
        require(backlogHours.isFinite() && backlogHours > 0.0 && backlogHours <= 1000.0)
    }
}

const val NotificationModeEvery: String = "every"
const val NotificationModeOff: String = "off"
const val NotificationModeBacklog: String = "backlog"
val FictionNotificationModes: List<String> = listOf(NotificationModeEvery, NotificationModeOff, NotificationModeBacklog)

fun canConfigureFictionNotifications(capabilities: ServerCapabilities, isFollowed: Boolean): Boolean =
    capabilities.backlogNotifications && isFollowed

fun validBacklogHours(value: String): Double? = value.trim().toDoubleOrNull()?.takeIf {
    it.isFinite() && it > 0.0 && it <= 1000.0
}

fun notificationStatusLabel(settings: FictionNotificationSettings?): String = when (settings?.mode) {
    NotificationModeEvery -> "Every chapter"
    NotificationModeOff -> "Off"
    NotificationModeBacklog -> when (settings.backlogArmed) {
        true -> "Armed"
        false -> "Waiting"
        null -> "Status unavailable"
    }
    else -> "Status unavailable"
}

fun notificationStatusDescription(settings: FictionNotificationSettings?): String = when (settings?.mode) {
    NotificationModeEvery -> "Notifications on for every new chapter."
    NotificationModeOff -> "Notifications off."
    NotificationModeBacklog -> when (settings.backlogArmed) {
        true -> "Backlog alert armed."
        false -> "Listen below the saved threshold to re-arm the backlog alert."
        null -> "Backlog alert status unavailable."
    }
    else -> "Notification status unavailable."
}

fun notificationNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

fun remainingBacklogLabel(seconds: Double): String? {
    if (!seconds.isFinite() || seconds < 0.0) return null
    val minutes = ceil(seconds / 60.0).toLong()
    val duration = when {
        seconds == 0.0 -> "0 min"
        seconds < 60.0 -> "under a minute"
        minutes < 60 -> "$minutes min"
        minutes % 60L == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
    return "Ready to listen (1x): $duration"
}
