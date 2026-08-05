package dk.perspektiva.ttsroad.desktop

/**
 * Representative payloads from a TTSRoad **server 1.4.0** (`app/routers/mobile.py`).
 *
 * These are deliberately *complete* — including every field the desktop client does not model
 * (`generated_at`, `player_index`, `sub_status`, `tts_progress`, `audio_filesize`, `has_timings`,
 * `remaining_seconds`, `last_listened_at`, `device_id`, `expires_at`, `server.version`, ...).
 * That is the point: the parsing tests assert that unknown *additive* fields are ignored rather
 * than failing the whole response, which is how the client survives a server upgrade.
 */
object ServerFixtures {

    /** POST /api/mobile/login — 200. */
    val LOGIN_SUCCESS = """
        {
          "token": "ttsr_Zm9vYmFyYmF6cXV1eA",
          "token_type": "bearer",
          "device_id": 42,
          "expires_at": "2026-11-04T09:12:33.123456Z",
          "user": {"id": 1, "username": "admin", "is_admin": true},
          "server": {
            "name": "Perspektiva TTSRoad",
            "version": "1.4.0",
            "base_url": "https://ttsroad.example.com",
            "api_version": 1
          }
        }
    """.trimIndent()

    /** 401 with a plain-string detail (bad username/password). */
    val LOGIN_401_STRING_DETAIL = """{"detail": "Invalid username or password"}"""

    /** 401 with an object detail, no code supplied yet. */
    val LOGIN_401_TOTP_REQUIRED = """
        {"detail": {"message": "Two-factor authentication required", "totp_required": true}}
    """.trimIndent()

    /** 401 with an object detail, wrong code supplied — indistinguishable on the wire. */
    val LOGIN_401_TOTP_INVALID = """
        {"detail": {"message": "Invalid authentication code", "totp_required": true}}
    """.trimIndent()

    /** 429 login throttle (app/services/login_throttle.py). */
    val LOGIN_429_THROTTLED = """
        {"detail": {"message": "Too many failed attempts", "reason": "user_throttled", "retry_after": 900}}
    """.trimIndent()

    /** 401 emitted by the auth middleware for a token that no longer resolves. */
    val UNAUTHORIZED_TOKEN_EXPIRED = """
        {"detail": {"message": "This device session expired. Sign in again.", "reason": "token_expired"}}
    """.trimIndent()

    /**
     * GET /api/mobile/library — 200.
     *
     * Note the two DIFFERENT chapter shapes the same [dk.perspektiva.ttsroad.desktop.data
     * .ChapterSummary] has to decode: `continue_listening` uses `chapter_id`/`chapter_title`
     * with a flat `fiction_title`, while the chapters endpoint uses `id`/`title` with a nested
     * `playback` object.
     */
    val LIBRARY = """
        {
          "api_version": 1,
          "generated_at": "2026-08-06T09:12:33.123456Z",
          "user": {"id": 1, "username": "admin", "is_admin": true},
          "server": {"name": "Perspektiva TTSRoad", "version": "1.4.0", "base_url": "https://ttsroad.example.com"},
          "fictions": [
            {
              "id": 7,
              "title": "A Test Serial",
              "author": "Someone",
              "fiction_id": "12345",
              "slug": "a-test-serial",
              "voice": "en-GB-RyanNeural",
              "rate": "+0%",
              "cover_image_url": "https://cdn.royalroadcdn.com/covers/12345.jpg",
              "description": "A story.",
              "rating": 4.72,
              "rating_count": 318,
              "tags": ["LitRPG", "Progression"],
              "enabled": true,
              "created_at": "2026-01-02T03:04:05Z",
              "updated_at": "2026-08-01T03:04:05Z",
              "last_polled_at": "2026-08-06T08:00:00Z",
              "total_chapters": 10,
              "done_chapters": 6,
              "pending_chapters": 2,
              "error_chapters": 1,
              "processing_chapters": 1
            }
          ],
          "continue_listening": [
            {
              "fiction_id": 7,
              "fiction_slug": "a-test-serial",
              "fiction_title": "A Test Serial",
              "fiction_author": "Someone",
              "cover_image_url": "/cover/a-test-serial.jpg",
              "chapter_id": 101,
              "chapter_number": 3,
              "chapter_title": "Chapter 3 — The Descent",
              "resume_seconds": 412.5,
              "resume_time_label": "6:52",
              "resume_label": "Resume",
              "resume_percent": 34,
              "remaining_count": 7,
              "played_count": 2,
              "sort_ts": 1786000000.0,
              "audio_duration": 1200.0,
              "audio": {
                "filename": "0003.mp3",
                "path": "/audio/a-test-serial/0003.mp3",
                "url": "https://ttsroad.example.com/audio/a-test-serial/0003.mp3",
                "requires_bearer_auth": true
              }
            }
          ],
          "recent_chapters": [
            {
              "fiction_id": 7,
              "fiction_slug": "a-test-serial",
              "fiction_title": "A Test Serial",
              "cover_image_url": "/cover/a-test-serial.jpg",
              "chapter_id": 106,
              "chapter_number": 6,
              "chapter_title": "Chapter 6",
              "status": "done",
              "created_at": "2026-08-05T10:00:00Z",
              "published_at": "2026-08-05T09:00:00Z",
              "audio_duration": 980.0,
              "audio": {
                "filename": "0006.mp3",
                "path": "/audio/a-test-serial/0006.mp3",
                "url": "https://ttsroad.example.com/audio/a-test-serial/0006.mp3",
                "requires_bearer_auth": true
              }
            }
          ]
        }
    """.trimIndent()

    /** GET /api/mobile/fictions/7/chapters — 200. */
    val CHAPTERS = """
        {
          "api_version": 1,
          "fiction": {
            "id": 7,
            "title": "A Test Serial",
            "author": "Someone",
            "fiction_id": "12345",
            "slug": "a-test-serial",
            "voice": "en-GB-RyanNeural",
            "rate": "+0%",
            "cover_image_url": "https://cdn.royalroadcdn.com/covers/12345.jpg",
            "description": "A story.",
            "rating": 4.72,
            "rating_count": 318,
            "tags": ["LitRPG"],
            "enabled": true,
            "total_chapters": 10,
            "done_chapters": 6,
            "pending_chapters": 2,
            "error_chapters": 1,
            "processing_chapters": 1
          },
          "total": 2,
          "chapters": [
            {
              "id": 101,
              "fiction_id": 7,
              "title": "Chapter 3 — The Descent",
              "chapter_number": 3,
              "display_number": 3,
              "player_index": 0,
              "url": "https://www.royalroad.com/fiction/12345/chapter/101",
              "published_at": "2026-07-01T09:00:00Z",
              "created_at": "2026-07-01T09:05:00Z",
              "updated_at": "2026-07-01T09:30:00Z",
              "status": "done",
              "sub_status": null,
              "tts_progress": 100,
              "error_message": null,
              "excluded": false,
              "playable": true,
              "audio_duration": 1200.0,
              "audio_duration_label": "20:00",
              "audio_filesize": 19200000,
              "has_timings": true,
              "audio": {
                "filename": "0003.mp3",
                "path": "/audio/a-test-serial/0003.mp3",
                "url": "https://ttsroad.example.com/audio/a-test-serial/0003.mp3",
                "requires_bearer_auth": true
              },
              "playback": {
                "position_seconds": 412.5,
                "is_played": false,
                "last_listened_at": "2026-08-06T08:55:00Z",
                "remaining_seconds": 787.5,
                "remaining_label": "13:07 left"
              }
            },
            {
              "id": 102,
              "fiction_id": 7,
              "title": "Chapter 4",
              "chapter_number": 4,
              "display_number": 4,
              "player_index": null,
              "url": "https://www.royalroad.com/fiction/12345/chapter/102",
              "published_at": null,
              "created_at": "2026-07-02T09:05:00Z",
              "updated_at": "2026-07-02T09:05:00Z",
              "status": "processing",
              "sub_status": "converting",
              "tts_progress": 41,
              "error_message": null,
              "excluded": false,
              "playable": false,
              "audio_duration": 0.0,
              "audio_duration_label": "0:00",
              "audio_filesize": 0,
              "has_timings": false,
              "audio": null,
              "playback": {
                "position_seconds": 0.0,
                "is_played": false,
                "last_listened_at": null,
                "remaining_seconds": 0.0,
                "remaining_label": "0:00 left"
              }
            }
          ]
        }
    """.trimIndent()

    /**
     * The same chapter payload as a hypothetical future server would send it: extra top-level
     * keys, extra keys inside `audio` and `playback`, and a brand-new nested object. Nothing here
     * is modelled by the client, and none of it may break decoding.
     */
    val CHAPTERS_WITH_UNKNOWN_ADDITIVE_FIELDS = """
        {
          "api_version": 2,
          "generated_at": "2027-01-01T00:00:00Z",
          "delta_token": "abc123",
          "fiction": {"id": 7, "title": "A Test Serial", "total_chapters": 1, "done_chapters": 1,
                      "content_rating": "teen", "series": {"id": 3, "name": "Arc One"}},
          "total": 1,
          "chapters": [
            {
              "id": 101,
              "fiction_id": 7,
              "title": "Chapter 3",
              "display_number": 3,
              "status": "done",
              "playable": true,
              "audio_duration": 1200.0,
              "content_hash": "sha256:deadbeef",
              "bookmarks": [{"id": 9, "position_seconds": 12.0}],
              "audio": {
                "filename": "0003.mp3",
                "path": "/audio/a-test-serial/0003.mp3",
                "url": "/audio/a-test-serial/0003.mp3",
                "requires_bearer_auth": true,
                "content_hash": "sha256:deadbeef",
                "bitrate_kbps": 128
              },
              "playback": {
                "position_seconds": 10.0,
                "is_played": false,
                "remaining_label": "19:50 left",
                "device_id": 42,
                "synced_at": "2027-01-01T00:00:00Z"
              }
            }
          ]
        }
    """.trimIndent()

    /** POST /api/mobile/playback/progress — 200. */
    val PROGRESS_SAVED = """{"status": "saved", "chapter_id": 101}"""

    /** POST /api/mobile/playback/mark — 200. */
    val MARK_OK = """{"status": "ok", "played": true, "chapter_ids": [101], "count": 1}"""
}

/**
 * Parses the fixtures above with exactly the Moshi configuration the repository uses, so UI tests
 * are driven by real server payloads instead of hand-built model objects.
 */
object ParsedFixtures {
    private val moshi = com.squareup.moshi.Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()

    val library: dk.perspektiva.ttsroad.desktop.data.LibraryResponse
        get() = requireNotNull(
            moshi.adapter(dk.perspektiva.ttsroad.desktop.data.LibraryResponse::class.java)
                .fromJson(ServerFixtures.LIBRARY),
        )

    val chapters: dk.perspektiva.ttsroad.desktop.data.ChaptersResponse
        get() = requireNotNull(
            moshi.adapter(dk.perspektiva.ttsroad.desktop.data.ChaptersResponse::class.java)
                .fromJson(ServerFixtures.CHAPTERS),
        )
}
