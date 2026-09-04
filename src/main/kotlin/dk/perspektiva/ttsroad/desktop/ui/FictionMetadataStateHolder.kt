package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.CoverImageFormats
import dk.perspektiva.ttsroad.desktop.data.CoverUploadResult
import dk.perspektiva.ttsroad.desktop.data.FictionMetadataFields
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.FictionTagLimits
import dk.perspektiva.ttsroad.desktop.data.FictionUpdateRequest
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.MobileVoice
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.cleanFictionTags
import dk.perspektiva.ttsroad.desktop.data.normaliseVoiceRate
import dk.perspektiva.ttsroad.desktop.data.userFacingMessage
import dk.perspektiva.ttsroad.desktop.data.voiceChangeConsequence
import dk.perspektiva.ttsroad.desktop.data.voiceRateProblem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is currently typed into the form. Separate from the fiction, which is what the server holds. */
data class FictionMetadataDraft(
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    /** The half-typed tag in the "add a tag" field, which is not a tag until it is committed. */
    val tagDraft: String = "",
    val voice: String = "",
    /** Speech rate for the next conversion. Blank means the fiction had none to show. */
    val rate: String = "",
)

data class FictionMetadataUiState(
    /** The fiction as the server last confirmed it — the baseline every "changed?" answer is against. */
    val fiction: FictionSummary? = null,
    val draft: FictionMetadataDraft = FictionMetadataDraft(),
    /** A picked image that has not been uploaded yet. */
    val chosenCover: File? = null,
    /** The server's cover ceiling if it published one; null means "let the server judge". */
    val maxCoverBytes: Long? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Cleared once this server has answered 404 to a cover upload — it predates the route. */
    val coverUploadSupported: Boolean = true,
    /** The narrator catalogue, or null when the server has no `/voices` route. */
    val voices: List<MobileVoice>? = null,
) {
    /** Which fields a person owns, as the server reports them. Empty on a server without the idea. */
    val overrides: Set<String> get() = fiction?.metadataOverrides?.toSet().orEmpty()

    /** Which metadata fields the form would send. Sending only these is what stops silent claims. */
    val changedFields: Set<String>
        get() {
            val current = fiction ?: return emptySet()
            val changed = mutableSetOf<String>()
            if (draft.title.trim() != current.title.trim()) changed += FictionMetadataFields.Title
            if (draft.author.trim() != current.author.orEmpty().trim()) changed += FictionMetadataFields.Author
            if (draft.description.trim() != current.description.orEmpty().trim()) {
                changed += FictionMetadataFields.Description
            }
            if (cleanFictionTags(draft.tags) != cleanFictionTags(current.tags)) {
                changed += FictionMetadataFields.Tags
            }
            return changed
        }

    /** Narration voice is a conversion setting, not metadata: changing it claims nothing. */
    val voiceChanged: Boolean get() = fiction != null && draft.voice.trim() != fiction.voice.orEmpty().trim()

    /**
     * Compared *normalised*, so re-typing `10` over a stored `+10%` is not a change.
     * Otherwise every visit to the screen would offer to save the same rate back.
     */
    val rateChanged: Boolean
        get() = fiction != null &&
            normaliseVoiceRate(draft.rate) != normaliseVoiceRate(fiction.rate)

    /** Why the typed rate cannot be sent, or null. Nothing downstream of here checks it. */
    val rateProblem: String? get() = voiceRateProblem(draft.rate)

    /** Whether a picker can be drawn: the server published a catalogue for this screen. */
    val canPickVoice: Boolean get() = voices != null

    /** What the narration change will and will not do, at the point of making it. */
    val narrationConsequence: String?
        get() = voiceChangeConsequence(
            doneChapters = fiction?.doneChapters ?: 0,
            voiceChanged = voiceChanged,
            rateChanged = rateChanged,
        )

    val hasChanges: Boolean
        get() = changedFields.isNotEmpty() || voiceChanged || rateChanged || chosenCover != null ||
            draft.tagDraft.isNotBlank()

    val canSave: Boolean get() = fiction != null && !isBusy && hasChanges && rateProblem == null
}

/**
 * The fiction editor: what a person may correct about a shared fiction, and what that costs.
 *
 * The costly half is the reason this is a screen with a holder rather than another dialog. On a
 * server that supports hand-edited metadata, **writing a metadata field takes ownership of it** —
 * the field stops being refreshed from the source, permanently, until somebody hands it back. So
 * the editor is careful about what it sends: only fields whose value actually changed go into the
 * request, and "use source values" sends `clear_overrides` *without* the values, because sending a
 * value would re-claim the field the same instant it was released.
 *
 * Both gates from [FictionManagementStateHolder] still apply above this holder — the advertised
 * `fiction_management` capability and the authoritative `is_admin` from `/api/mobile/me`. Nothing
 * here re-decides authorization; the server refuses a non-admin with a 403 regardless.
 */
class FictionMetadataStateHolder(
    private val repository: TtsRoadRepository,
    private val cache: LibraryCache,
    /** The native image chooser. Substituted in a test, which has neither a display nor a person. */
    private val picker: CoverImagePicker = DesktopCoverImagePicker,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder(dispatcher) {
    private val _state = MutableStateFlow(FictionMetadataUiState())
    val state: StateFlow<FictionMetadataUiState> = _state.asStateFlow()

    private var saveJob: Job? = null
    private var voicesJob: Job? = null

    /**
     * Points the form at [fiction], or refreshes the baseline of the one already open.
     *
     * Re-entering the same fiction keeps whatever is typed: the screen calls this again every time
     * a fresher copy arrives from the library cache, and a form that reset itself under a poll
     * would lose an edit somebody was halfway through.
     */
    fun load(fiction: FictionSummary, maxCoverBytes: Long? = null, voiceCatalogue: Boolean = false) {
        if (fiction.id <= 0) return
        _state.update { current ->
            if (current.fiction?.id == fiction.id) {
                current.copy(fiction = fiction, maxCoverBytes = maxCoverBytes)
            } else {
                // A different fiction resets the form, but the catalogue is a property of the
                // server rather than of the book, so it is carried across rather than re-fetched.
                FictionMetadataUiState(
                    fiction = fiction,
                    draft = draftOf(fiction),
                    maxCoverBytes = maxCoverBytes,
                    voices = current.voices,
                )
            }
        }
        if (voiceCatalogue) loadVoices()
    }

    /**
     * Fetch the narrator catalogue, once, in the background.
     *
     * Silent on failure by design: the picker is an improvement on a field that still works, and an
     * error banner for a list nobody asked for would report a problem the user does not have. A null
     * catalogue simply leaves the voice typed.
     */
    private fun loadVoices() {
        if (_state.value.voices != null || voicesJob?.isActive == true) return
        voicesJob = scope.launch {
            runCatching { repository.voices() }
                .onSuccess { voices -> _state.update { it.copy(voices = voices) } }
        }
    }

    fun setTitle(value: String) = editDraft { it.copy(title = value) }

    fun setAuthor(value: String) = editDraft { it.copy(author = value) }

    fun setDescription(value: String) = editDraft { it.copy(description = value) }

    fun setVoice(value: String) = editDraft { it.copy(voice = value) }

    fun setRate(value: String) = editDraft { it.copy(rate = value) }

    fun setTagDraft(value: String) = editDraft { it.copy(tagDraft = value) }

    /** Turns the "add a tag" text into a tag. A duplicate or a blank simply clears the field. */
    fun commitTagDraft() {
        editDraft { draft ->
            val merged = cleanFictionTags(draft.tags + draft.tagDraft)
            draft.copy(tags = merged, tagDraft = "")
        }
    }

    fun removeTag(tag: String) = editDraft { draft -> draft.copy(tags = draft.tags - tag) }

    /**
     * Opens the native picker and checks what comes back before anything is sent.
     *
     * The format check is not belt-and-braces: AWT's filename filter is a hint several Linux window
     * managers ignore, and every problem below is a 400 or a 413 that would otherwise arrive after
     * the whole image went over the wire.
     */
    fun chooseCover() {
        val current = _state.value
        if (current.fiction == null || current.isBusy || !current.coverUploadSupported) return
        val chosen = picker.choose() ?: return
        val problem = coverProblem(chosen, current.maxCoverBytes)
        _state.update {
            if (problem != null) {
                it.copy(error = problem)
            } else {
                it.copy(chosenCover = chosen, error = null, notice = null)
            }
        }
    }

    fun clearChosenCover() {
        _state.update { it.copy(chosenCover = null, error = null) }
    }

    /**
     * Sends the changed fields, then the chosen cover.
     *
     * In that order because the cover is a separate request: a metadata edit that succeeded is kept
     * and published even when the image is refused afterwards, and the screen says which half
     * happened rather than reporting one failure for both.
     */
    fun save() {
        val start = _state.value.let { it.copy(draft = it.draft.withCommittedTag()) }
        val fiction = start.fiction ?: return
        if (start.isBusy) return
        val validation = when {
            start.draft.title.isBlank() -> "Title cannot be empty"
            start.draft.voice.isBlank() -> "Voice cannot be empty"
            // Checked here and not only on the button: a disabled control is a courtesy, not a
            // guarantee, and this is the last point before a string the server never validates.
            else -> voiceRateProblem(start.draft.rate)
        }
        if (validation != null) {
            _state.value = start.copy(error = validation, notice = null)
            return
        }
        val patch = patchOf(start)
        val cover = start.chosenCover
        if (patch == null && cover == null) {
            _state.value = start.copy(error = null, notice = "Nothing to save")
            return
        }
        _state.value = start.copy(isBusy = true, error = null, notice = null)
        saveJob?.cancel()
        saveJob = scope.launch {
            runCatching {
                var latest = fiction
                var ignored = emptySet<String>()
                var coverProblem: String? = null
                var coverSupported = true
                if (patch != null) {
                    latest = repository.updateFiction(fiction.id, patch)
                    ignored = ignoredFields(patch, latest)
                }
                if (cover != null) {
                    when (val outcome = repository.uploadFictionCover(fiction.id, cover)) {
                        is CoverUploadResult.Saved -> latest = outcome.fiction
                        is CoverUploadResult.Rejected -> coverProblem = outcome.message
                        CoverUploadResult.Unsupported -> {
                            coverSupported = false
                            coverProblem = "This server cannot replace cover art from a client"
                        }
                    }
                }
                SaveOutcome(latest, ignored, coverProblem, coverSupported)
            }
                .onSuccess { outcome ->
                    cache.patchFiction(outcome.fiction)
                    cache.refreshLibrary()
                    cache.refreshBrowseAll()
                    _state.update {
                        it.copy(
                            fiction = outcome.fiction,
                            draft = draftOf(outcome.fiction),
                            // Kept only where the server refused the image for a reason a person can
                            // act on; there is nothing to retry against a server without the route.
                            chosenCover = outcome.chosenCoverToKeep(cover),
                            coverUploadSupported = outcome.coverSupported,
                            isBusy = false,
                            error = outcome.problem(),
                            notice = outcome.notice(),
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(isBusy = false, error = userFacingMessage(failure, "Could not save this fiction"))
                    }
                }
        }
    }

    /**
     * Hands [fields] back to the source, so the next refresh may write over them.
     *
     * Sends `clear_overrides` and nothing else. The values on screen do not change — clearing
     * removes the protection, it does not restore what the source used to say — so the draft is
     * deliberately left alone, including anything typed but not yet saved.
     */
    fun useSourceValues(fields: Set<String> = _state.value.overrides) {
        val current = _state.value
        val fiction = current.fiction ?: return
        val releasing = fields.intersect(current.overrides)
        if (current.isBusy || releasing.isEmpty()) return
        _state.update { it.copy(isBusy = true, error = null, notice = null) }
        saveJob?.cancel()
        saveJob = scope.launch {
            runCatching {
                repository.updateFiction(
                    fiction.id,
                    FictionUpdateRequest(clearOverrides = releasing.sorted()),
                )
            }
                .onSuccess { updated ->
                    cache.patchFiction(updated)
                    _state.update {
                        it.copy(
                            fiction = updated,
                            isBusy = false,
                            error = null,
                            notice = releasedNotice(releasing),
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = userFacingMessage(failure, "Could not hand those fields back"),
                        )
                    }
                }
        }
    }

    /** Puts the form back to what the server holds, dropping every unsaved edit. */
    fun revertEdits() {
        _state.update { current ->
            val fiction = current.fiction ?: return@update current
            if (current.isBusy) current else current.copy(
                draft = draftOf(fiction),
                chosenCover = null,
                error = null,
                notice = null,
            )
        }
    }

    fun sessionEnded() {
        saveJob?.cancel()
        _state.value = FictionMetadataUiState()
    }

    private fun editDraft(edit: (FictionMetadataDraft) -> FictionMetadataDraft) {
        _state.update { current ->
            if (current.fiction == null || current.isBusy) current
            else current.copy(draft = edit(current.draft), error = null, notice = null)
        }
    }

    /** What one save actually managed, which is not always all of it. */
    private data class SaveOutcome(
        val fiction: FictionSummary,
        val ignoredFields: Set<String>,
        val coverProblem: String?,
        val coverSupported: Boolean,
    ) {
        fun chosenCoverToKeep(chosen: File?): File? =
            chosen?.takeIf { coverProblem != null && coverSupported }

        fun problem(): String? = listOfNotNull(
            coverProblem,
            ignoredFields.takeIf { it.isNotEmpty() }?.let {
                "This server did not store " + humanList(it.map(FictionMetadataFields::labelOf)) +
                    " — it is older than hand-edited metadata"
            },
        ).joinToString(". ").takeIf { it.isNotEmpty() }

        fun notice(): String? =
            if (coverProblem == null && ignoredFields.isEmpty()) "Saved ${fiction.title}" else null
    }

    companion object {
        /** What is wrong with this image, or null when nothing is. Pure, so it needs no picker. */
        internal fun coverProblem(file: File, maxBytes: Long?): String? = when {
            !file.isFile -> "That file could not be read"
            !CoverImageFormats.isSupported(file.name) ->
                "Cover art has to be a ${CoverImageFormats.Description} image"
            file.length() == 0L -> "That file is empty"
            maxBytes != null && file.length() > maxBytes ->
                "That image is ${formatMegabytes(file.length())}; this server accepts up to " +
                    formatMegabytes(maxBytes)
            else -> null
        }

        /**
         * Fields the request set that the answer does not show.
         *
         * The server is the authority on what it stored, so the editor reads the response rather
         * than assuming the write landed. On a server that predates hand-edited metadata the
         * description and tags keys are simply dropped, and this is what notices that instead of
         * reporting a save that did not happen.
         */
        internal fun ignoredFields(sent: FictionUpdateRequest, answer: FictionSummary): Set<String> {
            val ignored = mutableSetOf<String>()
            sent.title?.let { if (answer.title.trim() != it.trim()) ignored += FictionMetadataFields.Title }
            sent.author?.let {
                if (answer.author.orEmpty().trim() != it.trim()) ignored += FictionMetadataFields.Author
            }
            sent.description?.let {
                if (answer.description.orEmpty().trim() != it.trim()) ignored += FictionMetadataFields.Description
            }
            sent.tags?.let {
                if (cleanFictionTags(answer.tags) != cleanFictionTags(it)) ignored += FictionMetadataFields.Tags
            }
            return ignored
        }

        /** The request for what changed, or null when nothing did. */
        internal fun patchOf(state: FictionMetadataUiState): FictionUpdateRequest? {
            val changed = state.changedFields
            if (changed.isEmpty() && !state.voiceChanged && !state.rateChanged) return null
            return FictionUpdateRequest(
                title = state.draft.title.trim().takeIf { FictionMetadataFields.Title in changed },
                author = state.draft.author.trim().takeIf { FictionMetadataFields.Author in changed },
                description = state.draft.description.trim()
                    .takeIf { FictionMetadataFields.Description in changed },
                tags = cleanFictionTags(state.draft.tags).takeIf { FictionMetadataFields.Tags in changed },
                voice = state.draft.voice.trim().takeIf { state.voiceChanged },
                // Normalised rather than sent as typed: the server stores this string without
                // checking it, so "10" would be saved and then fail at the next conversion.
                rate = normaliseVoiceRate(state.draft.rate).takeIf { state.rateChanged },
            )
        }

        internal fun draftOf(fiction: FictionSummary): FictionMetadataDraft = FictionMetadataDraft(
            title = fiction.title,
            author = fiction.author.orEmpty(),
            description = fiction.description.orEmpty(),
            tags = fiction.tags,
            voice = fiction.voice.orEmpty(),
            rate = fiction.rate.orEmpty(),
        )

        /** Folds a tag typed but never committed into the list, so Save does not silently drop it. */
        internal fun FictionMetadataDraft.withCommittedTag(): FictionMetadataDraft =
            if (tagDraft.isBlank()) copy(tagDraft = "") else copy(
                tags = cleanFictionTags(tags + tagDraft),
                tagDraft = "",
            )

        internal fun releasedNotice(released: Set<String>): String {
            val labels = humanList(released.map(FictionMetadataFields::labelOf).sorted())
            val verb = if (released.size == 1) "follows" else "follow"
            return "$labels now $verb the source again; the value here stays until the next refresh"
        }

        /** "Title", "Title and Tags", "Title, Tags and Cover art". */
        internal fun humanList(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items.single()
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }

        /** Tags are capped by the server; the form says so rather than letting a save truncate. */
        internal fun tagLimitLabel(count: Int): String =
            "$count/${FictionTagLimits.MaxTags} tags"

        private fun formatMegabytes(bytes: Long): String =
            String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
