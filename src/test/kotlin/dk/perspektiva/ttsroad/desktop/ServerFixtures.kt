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

    /** 401 after the session was revoked from the web console or another device. */
    val UNAUTHORIZED_TOKEN_REVOKED = """
        {"detail": {"message": "This device session was revoked. Sign in again.", "reason": "token_revoked"}}
    """.trimIndent()

    /** 401 for a token the server has never heard of (reset database, mangled value). */
    val UNAUTHORIZED_TOKEN_INVALID = """
        {"detail": {"message": "The bearer token is invalid.", "reason": "invalid_token"}}
    """.trimIndent()

    /** 401 from the auth middleware when no credential was supplied at all — a bare STRING detail. */
    val UNAUTHORIZED_NOT_AUTHENTICATED = """{"detail": "Not authenticated"}"""

    /**
     * GET /api/mobile/capabilities on server 1.4.0 (`app/routers/platform.py`).
     *
     * Only `readalong` and `device_management` are true there: the rest map to FastAPI route names
     * that do not exist yet. `batch_progress` being false while bulk marking works is the reason
     * capability flags are never inferred from behaviour.
     */
    val CAPABILITIES_1_4_0 = """
        {
          "api_version": 1,
          "server": {"name": "Perspektiva TTSRoad", "version": "1.4.0", "base_url": "https://ttsroad.example.com"},
          "capabilities": {
            "readalong": true,
            "search": false,
            "bookmarks": false,
            "delta_sync": false,
            "batch_progress": false,
            "audio_content_hash": false,
            "device_management": true
          },
          "limits": {"max_chapters_per_page": 200}
        }
    """.trimIndent()

    /**
     * A hypothetical future server: a capability this build has never heard of, a flag sent as a
     * string rather than a boolean, and a limit that is not a number.
     */
    val CAPABILITIES_WITH_UNKNOWN_KEYS = """
        {
          "api_version": 9,
          "server": {"name": "Future TTSRoad", "version": "2.9.0"},
          "capabilities": {
            "readalong": true,
            "search": "partial",
            "bookmarks": 1,
            "time_travel": true,
            "device_management": null
          },
          "limits": {"max_chapters_per_page": "lots", "max_bookmarks": 50}
        }
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

    /** GET /api/mobile/me — 200. */
    val ME = """{"user": {"id": 1, "username": "admin", "is_admin": true}}"""

    /**
     * GET /api/mobile/devices — 200 (`mobile_token_payload`, app/services/mobile_auth.py).
     *
     * Three rows on purpose: the current desktop session, a still-active phone, and a revoked one
     * the server keeps for 30 days after revocation. Note the timestamps are *second* precision
     * with a `Z` here, while login's `expires_at` carries microseconds — the same client has to
     * read both. The revoked row also carries `user_id`, which the client does not model.
     */
    val DEVICES = """
        {
          "api_version": 1,
          "devices": [
            {
              "id": 42,
              "user_id": 1,
              "device_name": "workstation · Windows 11",
              "created_at": "2026-08-01T09:00:00Z",
              "last_used_at": "2026-08-06T09:10:00Z",
              "expires_at": "2026-10-30T09:00:00Z",
              "last_ip": "192.168.1.20",
              "status": "active",
              "is_current": true
            },
            {
              "id": 43,
              "user_id": 1,
              "device_name": "Pixel 9",
              "created_at": "2026-07-02T18:30:00Z",
              "last_used_at": "2026-08-05T21:04:00Z",
              "expires_at": "2026-09-30T18:30:00Z",
              "last_ip": "10.0.0.5",
              "status": "active",
              "is_current": false
            },
            {
              "id": 44,
              "user_id": 1,
              "device_name": null,
              "created_at": "2026-06-01T10:00:00Z",
              "last_used_at": null,
              "expires_at": "2026-08-30T10:00:00Z",
              "last_ip": null,
              "status": "revoked",
              "is_current": false
            }
          ]
        }
    """.trimIndent()

    /** An account whose only session is this one. */
    val DEVICES_ONLY_CURRENT = """
        {
          "api_version": 1,
          "devices": [
            {
              "id": 42,
              "device_name": "workstation · Windows 11",
              "created_at": "2026-08-01T09:00:00Z",
              "last_used_at": "2026-08-06T09:10:00Z",
              "expires_at": "2026-10-30T09:00:00Z",
              "last_ip": "192.168.1.20",
              "status": "active",
              "is_current": true
            }
          ]
        }
    """.trimIndent()

    /**
     * A row from a server that fills in almost nothing, plus one unparseable date.
     *
     * The point of the fixture is that a malformed optional field costs that field and nothing
     * else: the row still lists, with dashes where the data is missing.
     */
    val DEVICES_MALFORMED_ROW = """
        {
          "devices": [
            {"id": 51, "last_used_at": "yesterday", "expires_at": "", "future_field": {"x": 1}}
          ]
        }
    """.trimIndent()

    /** DELETE /api/mobile/devices/{id} — 200. */
    val DEVICE_REVOKED = """{"status": "ok", "revoked": true, "token_id": 43}"""

    /** POST /api/mobile/devices/revoke-others — 200. */
    val DEVICES_REVOKED_OTHERS = """{"status": "ok", "revoked_count": 2}"""

    /** What a server without the device API answers on any of the three device routes. */
    val NOT_FOUND = """{"detail": "Not Found"}"""

    /** DELETE on a token that is already gone — the same 404 an old server sends. */
    val DEVICE_NOT_FOUND = """{"detail": "Active device session not found"}"""

    /** POST /api/mobile/playback/progress — 200. */
    val PROGRESS_SAVED = """{"status": "saved", "chapter_id": 101}"""

    /** POST /api/mobile/playback/mark — 200. */
    val MARK_OK = """{"status": "ok", "played": true, "chapter_ids": [101], "count": 1}"""

    /**
     * GET /api/mobile/bookmarks — 200, as `app/services/bookmarks.py` serialises it.
     *
     * Carries `user_id`, which this client deliberately does not model — every row already belongs
     * to the account that asked. It also carries a tombstone the server would normally have
     * filtered out and an `auto` breadcrumb, because the client must not depend on either being
     * absent: the list query decides, and the jump-back marks the web player writes live in this
     * same table.
     */
    val BOOKMARKS = """
        {
          "api_version": 1,
          "server_time": "2027-01-01T12:00:00Z",
          "updated_since": null,
          "bookmarks": [
            {
              "id": 9,
              "user_id": 1,
              "chapter_id": 101,
              "fiction_id": 7,
              "position_seconds": 742.5,
              "position_label": "12:22",
              "label": "The bridge scene",
              "note": "Come back to this for the epigraph.",
              "color": null,
              "kind": "manual",
              "created_at": "2027-01-01T09:00:00Z",
              "updated_at": "2027-01-01T09:00:00Z",
              "deleted_at": null,
              "chapter_title": "Chapter 3",
              "chapter_number": 3,
              "fiction_title": "A Test Serial",
              "fiction_slug": "a-test-serial"
            },
            {
              "id": 11,
              "user_id": 1,
              "chapter_id": null,
              "fiction_id": null,
              "position_seconds": 30.0,
              "position_label": "0:30",
              "label": "On a chapter that was deleted",
              "note": null,
              "color": null,
              "kind": "manual",
              "created_at": "2027-01-02T09:00:00Z",
              "updated_at": "2027-01-02T09:00:00Z",
              "deleted_at": null,
              "chapter_title": null,
              "chapter_number": null,
              "fiction_title": null,
              "fiction_slug": null
            },
            {
              "id": 12,
              "user_id": 1,
              "chapter_id": 101,
              "fiction_id": 7,
              "position_seconds": 5.0,
              "position_label": "0:05",
              "label": null,
              "note": null,
              "color": null,
              "kind": "manual",
              "created_at": "2026-12-30T09:00:00Z",
              "updated_at": "2027-01-03T09:00:00Z",
              "deleted_at": "2027-01-03T09:00:00Z",
              "chapter_title": "Chapter 3",
              "chapter_number": 3,
              "fiction_title": "A Test Serial",
              "fiction_slug": "a-test-serial"
            }
          ],
          "deleted": []
        }
    """.trimIndent()

    /** POST /api/mobile/bookmarks — 201. */
    val BOOKMARK_CREATED = """
        {
          "api_version": 1,
          "bookmark": {
            "id": 21,
            "user_id": 1,
            "chapter_id": 101,
            "fiction_id": 7,
            "position_seconds": 61.25,
            "position_label": "1:01",
            "label": null,
            "note": null,
            "color": null,
            "kind": "manual",
            "created_at": "2027-01-04T09:00:00Z",
            "updated_at": "2027-01-04T09:00:00Z",
            "deleted_at": null,
            "chapter_title": "Chapter 3",
            "chapter_number": 3,
            "fiction_title": "A Test Serial",
            "fiction_slug": "a-test-serial"
          }
        }
    """.trimIndent()

    /** DELETE /api/mobile/bookmarks/{id} — 200, and the same answer a second time. */
    val BOOKMARK_DELETED = """{"api_version": 1, "status": "deleted", "id": 9, "deleted_at": "2027-01-04T10:00:00Z"}"""

    /**
     * GET /api/mobile/search — 200, one hit in each of the three groups.
     *
     * Reproduces the shape `app/services/search.py` actually emits: one item type across all three
     * groups with nulls where a field does not apply, `highlights` as `[[start, end], …]` ranges
     * rather than markup, and `char_offset` present only on a narration-text hit.
     */
    val SEARCH = """
        {
          "api_version": 1,
          "query": "ashfall gate",
          "tokens": ["ashfall", "gate"],
          "scope": {"fiction_id": null},
          "limit": 20,
          "offset": 0,
          "indexed": true,
          "total": 3,
          "fictions": {
            "items": [
              {
                "kind": "fiction",
                "score": 160.0,
                "fiction_id": 7,
                "fiction_title": "A Test Serial",
                "fiction_slug": "a-test-serial",
                "author": "Someone",
                "cover_image_url": "https://ttsroad.example.com/static/covers/7.jpg",
                "tags": ["LitRPG"],
                "chapter_id": null,
                "chapter_title": null,
                "chapter_number": null,
                "audio_duration": 0.0,
                "status": null,
                "excluded": false,
                "playable": false,
                "has_timings": false,
                "audio": null,
                "char_offset": null,
                "matched_fields": ["title"],
                "snippet": "A Test Serial",
                "highlights": [[2, 6]],
                "url": "/fiction/7"
              }
            ],
            "total": 1,
            "capped": false,
            "has_more": false
          },
          "chapters": {
            "items": [
              {
                "kind": "chapter",
                "score": 140.0,
                "fiction_id": 7,
                "fiction_title": "A Test Serial",
                "fiction_slug": "a-test-serial",
                "author": null,
                "cover_image_url": "https://ttsroad.example.com/static/covers/7.jpg",
                "tags": [],
                "chapter_id": 102,
                "chapter_title": "The Ashfall Gate",
                "chapter_number": 2,
                "audio_duration": 1200.0,
                "status": "done",
                "excluded": false,
                "playable": true,
                "has_timings": true,
                "audio": {
                  "filename": "0002.mp3",
                  "url": "/api/mobile/audio/a-test-serial/0002.mp3",
                  "requires_bearer_auth": true
                },
                "char_offset": null,
                "matched_fields": ["chapter_title"],
                "snippet": "The Ashfall Gate",
                "highlights": [[4, 11]],
                "url": "/fiction/7?play=102"
              }
            ],
            "total": 1,
            "capped": false,
            "has_more": false
          },
          "text": {
            "items": [
              {
                "kind": "text",
                "score": 96.5,
                "fiction_id": 7,
                "fiction_title": "A Test Serial",
                "fiction_slug": "a-test-serial",
                "author": null,
                "cover_image_url": null,
                "tags": [],
                "chapter_id": 103,
                "chapter_title": "Descent",
                "chapter_number": 3,
                "audio_duration": 980.0,
                "status": "done",
                "excluded": false,
                "playable": true,
                "has_timings": false,
                "audio": {
                  "filename": "0003.mp3",
                  "url": "/api/mobile/audio/a-test-serial/0003.mp3",
                  "requires_bearer_auth": true
                },
                "char_offset": 4180,
                "matched_fields": ["clean_text"],
                "snippet": "…they came at last to the ashfall gate, and it was shut.",
                "highlights": [[26, 38]],
                "url": "/fiction/7?play=103"
              }
            ],
            "total": 1,
            "capped": false,
            "has_more": false
          }
        }
    """.trimIndent()

    /** A query nothing matched. Every group is present and empty — the server never omits one. */
    val SEARCH_EMPTY = """
        {
          "api_version": 1,
          "query": "nothingatall",
          "tokens": ["nothingatall"],
          "scope": {"fiction_id": null},
          "limit": 20,
          "offset": 0,
          "indexed": true,
          "total": 0,
          "fictions": {"items": [], "total": 0, "capped": false, "has_more": false},
          "chapters": {"items": [], "total": 0, "capped": false, "has_more": false},
          "text": {"items": [], "total": 0, "capped": false, "has_more": false}
        }
    """.trimIndent()

    /** A server with per-user libraries. Only `follows` differs from [CAPABILITIES_1_4_0]. */
    val CAPABILITIES_WITH_FOLLOWS = """
        {
          "api_version": 1,
          "server": {"name": "Perspektiva TTSRoad", "version": "1.5.0", "base_url": "https://ttsroad.example.com"},
          "capabilities": {
            "readalong": true,
            "follows": true,
            "device_management": true
          },
          "limits": {"max_chapters_per_page": 200}
        }
    """.trimIndent()

    /**
     * GET /api/mobile/library?scope=all — 200, on a server with per-user libraries.
     *
     * Two fictions, one followed and one not, plus the `scope` echo and the `following_ids` list.
     * The shelf payload has exactly the same shape with the unfollowed row absent.
     */
    val LIBRARY_BROWSE_ALL = """
        {
          "api_version": 1,
          "generated_at": "2026-08-11T09:12:33.123456Z",
          "scope": "all",
          "server_time": "2026-08-11T09:12:33.123456Z",
          "updated_since": null,
          "delta": false,
          "user": {"id": 1, "username": "admin", "is_admin": true},
          "server": {"name": "Perspektiva TTSRoad", "version": "1.5.0", "base_url": "https://ttsroad.example.com"},
          "fictions": [
            {
              "id": 7,
              "title": "A Test Serial",
              "author": "Someone",
              "slug": "a-test-serial",
              "cover_image_url": "https://cdn.royalroadcdn.com/covers/12345.jpg",
              "tags": ["LitRPG"],
              "total_chapters": 10,
              "done_chapters": 6,
              "pending_chapters": 2,
              "error_chapters": 1,
              "processing_chapters": 1,
              "following": true
            },
            {
              "id": 9,
              "title": "Someone Else's Serial",
              "author": "Another",
              "slug": "someone-elses-serial",
              "cover_image_url": null,
              "tags": [],
              "total_chapters": 4,
              "done_chapters": 4,
              "pending_chapters": 0,
              "error_chapters": 0,
              "processing_chapters": 0,
              "following": false
            }
          ],
          "following_ids": [7],
          "deleted": [],
          "continue_listening": [],
          "recent_chapters": []
        }
    """.trimIndent()

    /** POST /api/mobile/fictions/{id}/follow — 200. */
    val FOLLOWED = """
        {"api_version": 1, "status": "ok", "fiction_id": 7, "following": true, "created": true}
    """.trimIndent()

    /** DELETE /api/mobile/fictions/{id}/follow — 200. */
    val UNFOLLOWED = """
        {"api_version": 1, "status": "ok", "fiction_id": 7, "following": false, "removed": true}
    """.trimIndent()
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

    val search: dk.perspektiva.ttsroad.desktop.data.SearchResponse
        get() = requireNotNull(
            moshi.adapter(dk.perspektiva.ttsroad.desktop.data.SearchResponse::class.java)
                .fromJson(ServerFixtures.SEARCH),
        )

    val devices: List<dk.perspektiva.ttsroad.desktop.data.DeviceSession>
        get() = devicesFrom(ServerFixtures.DEVICES)

    fun devicesFrom(json: String): List<dk.perspektiva.ttsroad.desktop.data.DeviceSession> =
        requireNotNull(
            moshi.adapter(dk.perspektiva.ttsroad.desktop.data.DevicesResponse::class.java).fromJson(json),
        ).devices
}
