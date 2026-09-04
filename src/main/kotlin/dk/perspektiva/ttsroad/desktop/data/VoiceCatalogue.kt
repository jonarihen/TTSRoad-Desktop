package dk.perspektiva.ttsroad.desktop.data

import java.util.Locale

/**
 * Turning `GET /api/mobile/voices` into something pickable (#109).
 *
 * The server publishes the edge-tts catalogue: several hundred narrators across a hundred-odd
 * locales, spelled `en-US-BrianNeural`. Until this existed the desktop rendered a text field, so
 * choosing a voice meant knowing Microsoft's exact spelling and a typo was stored without complaint.
 *
 * Three things here are decisions rather than mechanics.
 *
 * [canPickVoice] takes **both** halves of the gate. Listing voices is open to any signed-in account
 * and *applying* one is admin-gated by the `PATCH`, so the capability alone would draw a picker for
 * a regular account whose save is a 403.
 *
 * [normaliseVoiceRate] exists because nothing else checks. `FictionUpdate.rate` on the server is a
 * bare `Optional[str]` with no validator, so `10` or `10%` saves cleanly, looks right on screen, and
 * then fails at the *next conversion* as a chapter that will not narrate — hours later, with nothing
 * on screen connecting the two. This is the only place between the keyboard and the database that
 * can catch it.
 *
 * [voiceChangeConsequence] says what a voice change does *not* do. Existing chapters keep the audio
 * they were made with; the choice applies to what the server converts next. Making it retroactive is
 * re-converting every chapter, which is hours of TTS and a separate decision under
 * `fiction_maintenance`.
 */

/** Both halves of the gate: the server has the catalogue, and this account may store a choice. */
fun canPickVoice(capabilities: ServerCapabilities, isAdmin: Boolean): Boolean =
    capabilities.voiceCatalogue && isAdmin

/**
 * One narrator, as the picker draws it.
 *
 * [name] is what gets stored and is never derived from anything else. [shortName] is only ever a
 * label — `en-US-BrianNeural` shown as "Brian" — and both are on screen, because two locales'
 * "Brian" are different narrators and the full name is the only thing that tells them apart.
 */
data class VoiceChoice(
    val name: String,
    val shortName: String,
    val locale: String,
    val gender: String?,
) {
    /** The line under the name: "Female · en-GB-SoniaNeural", or just the name when no gender came. */
    val detail: String
        get() = gender?.takeIf { it.isNotBlank() }?.let { "$it  ·  $name" } ?: name
}

/** The voices of one locale, under the name that locale has in the reader's own language. */
data class VoiceGroup(
    /** The server's own tag — `en-US` — which is what the grouping is keyed on. */
    val locale: String,
    /** "English (United States)", or the raw tag when the platform cannot name it. */
    val label: String,
    val voices: List<VoiceChoice>,
)

/** Voices whose row carried no locale at all. Kept rather than dropped: the name still works. */
const val UnknownVoiceLocale: String = "other"

/**
 * The catalogue as groups, ordered so the useful one is first.
 *
 * The ordering is the point, and it is not alphabetical:
 *
 * 1. The locale of [current] — the voice this fiction already uses. Someone opening the picker is
 *    usually moving to a neighbouring voice, and starting anywhere else means scrolling back to
 *    where they already were.
 * 2. The locales matching the machine's own language, so a Danish desktop reading English serials
 *    still finds `da-DK` without a hundred locales of alphabet first.
 * 3. Everything else by label, so the tail is at least predictable.
 *
 * [query] filters on name, gender, locale tag and locale label, so both "sonia" and "british" find
 * `en-GB-SoniaNeural`. An empty result is an empty list — the caller says what that means.
 *
 * A row with a blank name is dropped: it cannot be stored, so offering it would be offering a
 * choice whose save fails. A name that arrives twice is kept once.
 */
fun voiceGroups(
    voices: List<MobileVoice>?,
    current: String? = null,
    query: String = "",
    locale: Locale = Locale.getDefault(),
): List<VoiceGroup> {
    if (voices.isNullOrEmpty()) return emptyList()
    val currentLocale = voices.firstOrNull { it.name == current?.trim() }?.localeTag
    val machineLanguage = locale.language.lowercase(Locale.ROOT)
    val wanted = query.trim().lowercase(Locale.ROOT)

    val groups = voices
        .filter { it.name.isNotBlank() }
        // One row per name. A name is the identity here, not a label, and the picker keys its list
        // on it — a server that published the same narrator twice would otherwise be a duplicate key
        // rather than merely a repeated row.
        .distinctBy { it.name.trim() }
        .groupBy { it.localeTag }
        .map { (tag, rows) ->
            VoiceGroup(
                locale = tag,
                label = voiceLocaleLabel(tag, locale),
                // By the name a human reads, not by the wire name: `en-US-AvaNeural` and
                // `en-US-AvaMultilingualNeural` belong next to each other.
                voices = rows.map { it.asChoice(tag) }.sortedBy { it.shortName.lowercase(Locale.ROOT) },
            )
        }
        .filter { it.voices.isNotEmpty() }

    val matched = if (wanted.isEmpty()) groups else groups.mapNotNull { group ->
        val hits = group.voices.filter { choice ->
            choice.name.lowercase(Locale.ROOT).contains(wanted) ||
                group.locale.lowercase(Locale.ROOT).contains(wanted) ||
                group.label.lowercase(Locale.ROOT).contains(wanted) ||
                choice.gender?.lowercase(Locale.ROOT)?.contains(wanted) == true
        }
        group.copy(voices = hits).takeIf { hits.isNotEmpty() }
    }

    return matched.sortedWith(
        compareBy(
            { if (it.locale == currentLocale) 0 else 1 },
            { if (it.locale.substringBefore('-').lowercase(Locale.ROOT) == machineLanguage) 0 else 1 },
            { it.label.lowercase(Locale.ROOT) },
        ),
    )
}

/**
 * Which group should already be open, or null when none should be.
 *
 * The one holding [current], so the voice in force is on screen without hunting — "which is it now"
 * and "what else is near it" are the same question. With no current voice there is nothing to be
 * near, and the first group opens instead.
 */
fun initiallyExpandedVoiceLocale(groups: List<VoiceGroup>, current: String?): String? {
    val name = current?.trim().orEmpty()
    if (name.isNotEmpty()) {
        groups.firstOrNull { group -> group.voices.any { it.name == name } }?.let { return it.locale }
    }
    return groups.firstOrNull()?.locale
}

/** `en-US` as "English (United States)", or the tag itself when the platform has no name for it. */
fun voiceLocaleLabel(tag: String, locale: Locale = Locale.getDefault()): String {
    if (tag == UnknownVoiceLocale) return "Other"
    val named = runCatching { Locale.forLanguageTag(tag).getDisplayName(locale) }.getOrNull()
    return named?.takeIf { it.isNotBlank() && it != tag } ?: tag
}

/** The locale this voice is filed under — the server's tag, or [UnknownVoiceLocale]. */
private val MobileVoice.localeTag: String
    get() = locale?.trim()?.takeIf { it.isNotEmpty() } ?: UnknownVoiceLocale

private fun MobileVoice.asChoice(tag: String) = VoiceChoice(
    name = name.trim(),
    shortName = shortVoiceName(name.trim(), tag),
    locale = tag,
    gender = gender?.trim()?.takeIf { it.isNotEmpty() },
)

/**
 * `en-US-BrianNeural` as "Brian".
 *
 * The locale prefix is stripped by the voice's own tag rather than by a pattern, which is what makes
 * `zh-CN-liaoning-XiaobeiNeural` come out as "Xiaobei": its locale really is `zh-CN-liaoning`, and a
 * two-letter-plus-region pattern would leave "liaoning" in the label. Anything that shortens to
 * nothing keeps its full name — a label is not worth losing the identity over.
 */
fun shortVoiceName(name: String, locale: String?): String {
    val prefix = locale?.takeIf { it.isNotBlank() && it != UnknownVoiceLocale }?.let { "$it-" }
    val stripped = when {
        prefix != null && name.startsWith(prefix, ignoreCase = true) -> name.substring(prefix.length)
        else -> name.substringAfterLast('-')
    }
    return stripped.removeSuffix("Neural").trim().ifEmpty { name }
}

/**
 * The rate as the server should store it, or null when this is not a rate.
 *
 * `[+-]NNN%` is the form edge-tts takes and the same expression the web console validates with
 * (`VP_RATE_RE` in `app.js`). A bare number is accepted and signed — "10" is unambiguously "+10%",
 * and nobody should have to remember that the plus is load-bearing.
 *
 * Zero is the one case where the sign is not a choice: `-0%` and `+0%` are the same rate, so both
 * normalise to `+0%` rather than leaving two spellings of "unchanged" in the database.
 */
fun normaliseVoiceRate(input: String?): String? {
    val text = input?.trim().orEmpty()
    if (text.isEmpty()) return null
    val body = text.removeSuffix("%").trim()
    val negative = body.startsWith("-")
    val digits = body.removePrefix("+").removePrefix("-")
    if (digits.isEmpty() || digits.length > 3 || !digits.all { it.isDigit() }) return null
    val magnitude = digits.toInt()
    return if (negative && magnitude > 0) "-$magnitude%" else "+$magnitude%"
}

/** Why that rate cannot be sent, or null when it can. Empty is not a problem — it means "leave it". */
fun voiceRateProblem(input: String?): String? = when {
    input.isNullOrBlank() -> null
    normaliseVoiceRate(input) != null -> null
    else -> "A rate reads like +0%, +25% or -10%."
}

/**
 * What this change will and will not do, in words, at the point of making it.
 *
 * Null when nothing about the narration is changing. Otherwise it names the half that a picker
 * silently implies is included: the chapters that exist keep the audio they were made with, and the
 * choice applies to whatever is converted next.
 */
fun voiceChangeConsequence(
    doneChapters: Int,
    voiceChanged: Boolean,
    rateChanged: Boolean,
): String? {
    if (!voiceChanged && !rateChanged) return null
    val what = when {
        voiceChanged && rateChanged -> "This voice and rate are"
        voiceChanged -> "This voice is"
        else -> "This rate is"
    }
    val existing = when (doneChapters) {
        0 -> "Nothing has been converted yet, so nothing is affected."
        1 -> "The one chapter already converted keeps the audio it was made with."
        else ->
            "The $doneChapters chapters already converted keep the audio they were made with — " +
                "nothing is re-narrated."
    }
    return "$existing $what used for whatever the server converts next."
}
