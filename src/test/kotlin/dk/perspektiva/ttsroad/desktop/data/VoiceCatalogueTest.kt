package dk.perspektiva.ttsroad.desktop.data

import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class VoiceCatalogueTest {

    private val catalogue = listOf(
        MobileVoice("en-US-BrianNeural", "en-US", "Male"),
        MobileVoice("en-US-AvaMultilingualNeural", "en-US", "Female"),
        MobileVoice("en-US-AvaNeural", "en-US", "Female"),
        MobileVoice("en-GB-SoniaNeural", "en-GB", "Female"),
        MobileVoice("da-DK-ChristelNeural", "da-DK", "Female"),
        MobileVoice("zh-CN-liaoning-XiaobeiNeural", "zh-CN-liaoning", "Female"),
    )

    @Test
    fun `the current voice's locale sorts first, then the machine's language`() {
        val groups = voiceGroups(catalogue, current = "en-GB-SoniaNeural", locale = Locale.forLanguageTag("da-DK"))

        assertEquals("en-GB", groups.first().locale, "the fiction's own locale opens the list")
        assertEquals("da-DK", groups[1].locale, "then the machine's own language")
    }

    @Test
    fun `with no current voice the machine's language leads`() {
        val groups = voiceGroups(catalogue, locale = Locale.forLanguageTag("da-DK"))

        assertEquals("da-DK", groups.first().locale)
    }

    @Test
    fun `voices are ordered by the name a human reads, not the wire name`() {
        val group = voiceGroups(catalogue, locale = Locale.US).first { it.locale == "en-US" }

        assertContentEquals(
            listOf("Ava", "AvaMultilingual", "Brian"),
            group.voices.map { it.shortName },
            "AvaNeural and AvaMultilingualNeural belong next to each other",
        )
    }

    @Test
    fun `a three-segment locale is stripped by its own tag`() {
        val group = voiceGroups(catalogue, locale = Locale.US).first { it.locale == "zh-CN-liaoning" }

        assertEquals(
            "Xiaobei",
            group.voices.single().shortName,
            "a language-region pattern would have left 'liaoning' in the label",
        )
    }

    @Test
    fun `the stored name is never the display name`() {
        val choice = voiceGroups(catalogue, locale = Locale.US)
            .flatMap { it.voices }
            .first { it.shortName == "Brian" }

        assertEquals("en-US-BrianNeural", choice.name)
        assertEquals("Male  ·  en-US-BrianNeural", choice.detail)
    }

    @Test
    fun `search matches the name, the locale tag, the locale label and the gender`() {
        assertEquals(
            "en-GB-SoniaNeural",
            voiceGroups(catalogue, query = "sonia", locale = Locale.US).single().voices.single().name,
        )
        assertEquals(
            "en-GB",
            voiceGroups(catalogue, query = "united kingdom", locale = Locale.US).single().locale,
            "the label is searchable, so 'british-sounding' words find the group",
        )
        assertEquals("en-GB", voiceGroups(catalogue, query = "en-GB", locale = Locale.US).single().locale)
        assertTrue(voiceGroups(catalogue, query = "male", locale = Locale.US).isNotEmpty())
        assertTrue(voiceGroups(catalogue, query = "nothingmatchesthis", locale = Locale.US).isEmpty())
    }

    @Test
    fun `a nameless row is dropped rather than offered as an unsaveable choice`() {
        val groups = voiceGroups(listOf(MobileVoice("", "en-US", "Female")) + catalogue, locale = Locale.US)

        assertTrue(groups.flatMap { it.voices }.none { it.name.isBlank() })
        assertEquals(6, groups.flatMap { it.voices }.size)
    }

    @Test
    fun `a duplicated name is kept once`() {
        val groups = voiceGroups(catalogue + MobileVoice("en-US-BrianNeural", "en-US", "Male"), locale = Locale.US)

        assertEquals(1, groups.flatMap { it.voices }.count { it.name == "en-US-BrianNeural" })
    }

    @Test
    fun `a voice with no locale is filed under Other rather than dropped`() {
        val groups = voiceGroups(listOf(MobileVoice("SomeLegacyVoice")), locale = Locale.US)

        assertEquals(UnknownVoiceLocale, groups.single().locale)
        assertEquals("Other", groups.single().label)
        assertEquals("SomeLegacyVoice", groups.single().voices.single().name)
    }

    @Test
    fun `an empty catalogue is an empty list, not a group holding nothing`() {
        assertTrue(voiceGroups(null).isEmpty())
        assertTrue(voiceGroups(emptyList()).isEmpty())
    }

    @Test
    fun `the group holding the current voice is the one that opens`() {
        val groups = voiceGroups(catalogue, current = "da-DK-ChristelNeural", locale = Locale.US)

        assertEquals("da-DK", initiallyExpandedVoiceLocale(groups, "da-DK-ChristelNeural"))
        assertEquals(groups.first().locale, initiallyExpandedVoiceLocale(groups, null))
        assertNull(initiallyExpandedVoiceLocale(emptyList(), "en-US-BrianNeural"))
    }

    @Test
    fun `a bare number is signed rather than refused`() {
        assertEquals("+10%", normaliseVoiceRate("10"))
        assertEquals("+10%", normaliseVoiceRate("10%"))
        assertEquals("+10%", normaliseVoiceRate("+10%"))
        assertEquals("-10%", normaliseVoiceRate("-10"))
        assertEquals("-10%", normaliseVoiceRate(" -10% "))
    }

    @Test
    fun `zero has one spelling`() {
        assertEquals("+0%", normaliseVoiceRate("0"))
        assertEquals("+0%", normaliseVoiceRate("-0%"), "minus zero and plus zero are the same rate")
        assertEquals("+0%", normaliseVoiceRate("+0%"))
    }

    @Test
    fun `a rate that is not a rate is refused before it can be stored`() {
        assertNull(normaliseVoiceRate("fast"))
        assertNull(normaliseVoiceRate("++10%"))
        assertNull(normaliseVoiceRate("1000"), "four digits is not a rate edge-tts takes")
        assertNull(normaliseVoiceRate("10.5"))
        assertNull(normaliseVoiceRate("%"))
        assertNull(normaliseVoiceRate("-"))
    }

    @Test
    fun `an empty rate is not a problem - it means leave it alone`() {
        assertNull(normaliseVoiceRate(""))
        assertNull(normaliseVoiceRate(null))
        assertNull(voiceRateProblem(""))
        assertNull(voiceRateProblem(null))
        assertNull(voiceRateProblem("  "))
    }

    @Test
    fun `a malformed rate reports the shape it should have had`() {
        val problem = voiceRateProblem("fast")

        assertNotNull(problem)
        assertTrue(problem.contains("+25%"), "the message names the form rather than only refusing")
        assertNull(voiceRateProblem("-10%"))
    }

    @Test
    fun `both halves of the gate are required to draw a picker`() {
        val capable = ServerCapabilities(voiceCatalogue = true)

        assertTrue(canPickVoice(capable, isAdmin = true))
        assertTrue(
            !canPickVoice(capable, isAdmin = false),
            "listing is open to anyone but applying is admin-gated, so this would be a 403 on save",
        )
        assertTrue(!canPickVoice(ServerCapabilities.Baseline, isAdmin = true))
    }

    @Test
    fun `the consequence names what keeps its existing audio`() {
        assertNull(voiceChangeConsequence(doneChapters = 4, voiceChanged = false, rateChanged = false))

        val many = voiceChangeConsequence(doneChapters = 4, voiceChanged = true, rateChanged = false)
        assertNotNull(many)
        assertTrue(many.contains("4 chapters already converted"))
        assertTrue(many.contains("This voice is"))

        val one = voiceChangeConsequence(doneChapters = 1, voiceChanged = false, rateChanged = true)
        assertNotNull(one)
        assertTrue(one.contains("The one chapter"))
        assertTrue(one.contains("This rate is"))

        val none = voiceChangeConsequence(doneChapters = 0, voiceChanged = true, rateChanged = true)
        assertNotNull(none)
        assertTrue(none.contains("Nothing has been converted yet"))
        assertTrue(none.contains("This voice and rate are"))
    }

    @Test
    fun `a locale with no display name falls back to its own tag`() {
        assertEquals("Other", voiceLocaleLabel(UnknownVoiceLocale))
        assertEquals("English (United States)", voiceLocaleLabel("en-US", Locale.US))
    }

    @Test
    fun `a name that shortens to nothing keeps its full name`() {
        assertEquals(
            "en-US-",
            shortVoiceName("en-US-", "en-US"),
            "stripping the prefix leaves nothing, and a label is not worth losing the identity over",
        )
    }

    @Test
    fun `a name with no locale still loses its Neural suffix`() {
        assertEquals("Brian", shortVoiceName("BrianNeural", null))
        assertEquals("Brian", shortVoiceName("en-US-BrianNeural", "en-US"))
    }
}
