# Faster playback with bounded service load

Improve playback after an explicit Play action and prepare only the immediate next episode near its playback threshold. Browsing home rows, focusing posters, and opening title pages must cause zero new stream searches, TorBox additions, playback-link requests, or video downloads. Build on the upstream merge at `9d333ee9e` and its compilation repairs at `8c35265a5`; do not apply `nuvio-fast-playback.zip` wholesale.

## Fixed decisions

| Decision | Value |
|---|---|
| Search trigger | Explicit Play/source-picker action, or one immediate-next-episode preparation near its threshold. No poster-focus searches. Details-page searches are allowed only through the "Addition 2026-11-21" coordinator and its settings toggles (IP tasks). |
| Public infrastructure | Use installed sources and existing shared search sessions. No bundled community add-on URLs, background refresh loops, duplicate searches, new scrapers, or service-limit increases. |
| Background work | At most one active next-episode target per player session. No season-wide preparation, unrelated movies, or downloads of uncached torrents. Speculative video reads are limited to the "Addition 2026-11-21" warm-up windows (IP tasks). |
| Preparation | Existing preparation setting remains opt-in; zero remains off. Cap this workflow to one selected cached source per media search. Keep the existing six/minute and thirty/hour background budgets. Foreground Play remains usable after those budgets are exhausted. |
| Availability reuse | TorBox only: account-key fingerprint plus normalized hash; memory only; positive TTL 120 seconds, negative TTL 30 seconds; 2,048 entries; no caching of failures/unknown results. Reuse concurrent requests. |
| Availability scheduling | Publish source groups promptly with CHECKING markers. At most two availability jobs active per search, one shared TorBox HTTP batch active at a time; at most 100 distinct hashes per request. Emit updates through one serialized accumulator. |
| Privacy and isolation | Preserve profile, provider, account, source configuration, episode identity, plugin variant and binge group. No keys, full URLs, headers, magnets, hashes, or titles in new diagnostic output. |
| Next episode | Keep existing settings. Collapse overlapping upstream source preload and fork link preparation into one target lifecycle. Begin at the existing configured preparation threshold, never on browsing or for every episode in a season. |
| Recovery | Refresh a rejected cached direct link once on explicit playback; no new source search. Do not add the ZIP automatic release-changing watchdog in this batch. |
| Player | Preserve current buffer modes, memory budgets, MP4 handling, frame-rate matching, MPV, ExoPlayer, Dialogue Leveler and upstream audio night mode. No large-MP4 exclusion. The "Addition 2026-11-21" tasks may change parallel-download defaults and add the MKV cluster resync wrapper exactly as their Fixed decisions state. |
| Verification | Mock service calls and use virtual time for behavioural checks. No public scraper traffic or account access from tests. Gradle runs under heavy_build with a hard deadline. |
| Delivery | Local implementation and automated verification only. No release, production change, push, or APK installation in this plan. |

## Worker notes

- One task per worker; the worker owns it until the server confirms it is integrated. The server verifies, merges, and brings the same worker back with exact details if a check or merge needs reasoning.
- Everything needed is in the task text and Fixed decisions. Do not ask the owner anything; nothing in this plan is the owner's to do.
- Files outside `Scope:` that the task truly needs are approved automatically (except protected files; sensitive ones need `SCOPE-REQUEST: path — reason`). A file another running task holds pauses the task until it is free.
- Run checks and shared tools through `agentq run --owner <task id> --defer-after 120 --timeout <seconds> -- <command>`. A busy resource is never a reason to stop or ask; if it answers QUEUED, commit and end the turn with `WAITING FOR <id>`.
- Before finishing, run `agentq task finalize <task id>`; exit 3 means not finished, so fix what it names and run it again.
- Do not merge, rebase onto main, push, or tick checkboxes; the queue records completion.

Every Gradle/compiler/build command additionally declares `--resource heavy_build`. Preserve existing uncommitted work and all unrelated services. Scope entries ending with a file name include only that file, not adjacent features. Extend the named existing test classes so baseline selection already works; add focused cases for the new behaviour rather than testing implementation details.

## Tasks

- [ ] **FP1 — Reuse TorBox availability answers and concurrent requests.**
  - Depends on: none.
  - Priority: 150
  - Scope: app/src/main/java/com/nuvio/tv/core/debrid/LocalDebridService.kt, app/src/test/java/com/nuvio/tv/core/debrid/LocalDebridAvailabilityServiceTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*LocalDebridAvailabilityServiceTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Read LocalDebridService.checkCached/checkTorboxCached and LocalDebridAvailabilityServiceTest. Add a bounded memory cache and concurrent-request reuse inside LocalDebridService using the availability limits above. Cache successful per-hash answers; preserve the public nullable-map contract and Premiumize behaviour. Split fresh unknown hashes into batches of at most 100, serialize TorBox network batches with a coroutine mutex, and recheck the cache after acquiring admission so overlapping batches do not repeat work. Keep network calls outside the cache-state mutex. Expose internal var nowMs: () -> Long = SystemClock::elapsedRealtime for TTL tests to override, keeping the existing injected constructor unchanged. Distinguish cached absence from unknown failure. Do not cache 401, 429, malformed responses or transport failures. An individual caller cancelling must not corrupt cache state or strand waiters. Credential fingerprint changes make prior answers inaccessible.
  - Acceptance: overlapping concurrent requests ask TorBox once per fresh hash; different accounts never share answers; TTL/entry limits hold; unknown results stay unknown; tests prove positive, negative, failure, expiry, overlap and cancellation cases without real HTTP calls.

- [ ] **FP2 — Show arriving sources without waiting behind unrelated TorBox checks.**
  - Depends on: FP1.
  - Priority: 150
  - Scope: app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt, app/src/test/java/com/nuvio/tv/data/repository/StreamRepositoryPluginIsolationTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*StreamRepositoryPluginIsolationTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs FP1's shared request reuse to keep availability scheduling from increasing redundant calls. Read fetchStreamsFromAllSources, mergePresentedResult, the server-source branch and StreamSearchSessionCache. Separate arrival of a source group from its availability result: publish CHECKING promptly, then publish its checked replacement. Use at most two active checking jobs and one event-consumer coroutine owning accumulatedResults and emitting snapshots. Hold further checking work in a pending map keyed by source group, bounded by the installed sources in this search, replacing obsolete pending versions of the same group. Scheduling a check must never suspend the event consumer behind a permit, since that would hide later direct playable sources. Launch the next pending group when a checking job reports completion; ignore obsolete group versions. Do not mutate published lists or emit from sibling coroutines of a flow builder. Complete a search only after all producer and availability jobs finish. Failed checks retain UNKNOWN. Propagate cancellation, reject obsolete/profile-switched session results, and preserve source ordering, server streams, plugin continuation, filtering and error summaries. Do not perform network work merely to serve getCachedStreamsFromAllAddons.
  - Acceptance: a hung first availability check does not hide a later direct playable source; CHECKING/UNKNOWN torrents never auto-play; final search completion includes checked results; concurrent groups cannot overwrite each other; add-on request counts are unchanged.

- [ ] **FP3 — Prepare one selected cached link as current search results arrive.**
  - Depends on: FP2.
  - Priority: 150
  - Scope: app/src/main/java/com/nuvio/tv/core/debrid/DirectDebridStreamPreparer.kt, app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreenViewModel.kt, app/src/test/java/com/nuvio/tv/core/debrid/DirectDebridStreamPreparerTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*DirectDebridStreamPreparerTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs FP2's incremental checked emissions. Read launchDirectDebridPreparationIfNeeded, prioritizeCandidates, DirectDebridResolver.cachedPlayableStream and existing preparation budgets. Replace the one-shot first-emission flag with a search-scoped preparation lifecycle. Coalesce candidate changes for 200 milliseconds using virtual-time-testable flow logic, then choose using the existing automatic-selection rules and current filtered list. Only prepare a confirmed cached candidate. Never bypass an explicit regex, provider/source selection, or binge-group constraint. Resolve at most one previously unresolved candidate per search, not one on every emission; if the first groups contain no eligible candidate, a later eligible group can start that one preparation. Reuse cached/in-flight resolutions and keep list rewriting off the main thread. Zero preparation setting remains off; existing nonzero values are retained in storage but this workflow caps work to one. Cancelling or changing profile/media closes the preparation lifecycle and prevents late publication into the wrong screen.
  - Acceptance: a late eligible source is prepared when early results were ineligible; ten updates do not create ten playback links; no browse-triggered network work is introduced; exhausted background budget does not block explicit Play.

- [ ] **FP4 — Refresh a rejected prepared TorBox link once without scraping again.**
  - Depends on: none.
  - Scope: app/src/main/java/com/nuvio/tv/core/debrid/DirectDebridResolver.kt, app/src/main/java/com/nuvio/tv/core/debrid/TorboxDirectDebridResolver.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt, app/src/test/java/com/nuvio/tv/core/debrid/TorboxDirectDebridResolverTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*TorboxDirectDebridResolverTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Read resolve, resolveToPlayableStream, directDebridResolveCacheKey and attemptStartupRecovery. Add targeted invalidation of one prepared resolution; never clear unrelated account or episode entries. Before first picture, a 403/404/410 from a prepared TorBox delivery URL can invalidate and re-resolve the same torrent/file once for that playback attempt, then use the existing recovery outcome. Store the original Stream plus season/episode and the current credential fingerprint alongside a successful in-memory resolution. Expose refreshPreparedTorboxUrl(url: String): DirectDebridResolveResult? to match that URL under the current account, invalidate only its entry and resolve the stored original once. Return null on an identity/account mismatch. Never alter public navigation routes or persist credentials. Do not treat a TorBox API authentication failure as a delivery-link expiry. If identity is absent, retain current error handling. Read success/error/detail from TorBox envelopes consistently when classifying cached-only refusal versus stale data, without assuming all failures have a particular HTTP status. No new source search, no alternative release, no retry loop, and no mid-film position reset.
  - Acceptance: one stale prepared link causes one fresh resolution and no scraper requests; a second rejection stops retrying; authentication/rate-limit/unknown failures remain distinguishable; different episodes/accounts retain their own links. Tests exercise observable request counts and results with fake responses.

- [ ] **FP5 — Unify the two immediate-next-episode preparation paths.**
  - Depends on: FP3, FP4.
  - Scope: app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/NextEpisodePreparationPolicy.kt, app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerNextEpisodeRulesTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*PlayerNextEpisodeRulesTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs FP3's bounded link preparation and FP4's stale-link recovery. Read preloadNextEpisodeSources/cancelNextEpisodePreload, prefetchNextEpisodeStream/clearNextEpisodePrefetch, playNextEpisode(prefetchOnly), and maybeShowNextEpisodeCard. Keep one immediate-next-episode target keyed by profile/content/video/season/episode/source configuration/binge-group continuation. Join the shared source search, select through the existing pipeline and prepare at most one cached link only when its existing opt-in preparation setting permits it. Existing source-only preload setting still works with preparation off. When fork link preparation and upstream source preload are both enabled, they must join one search, not start two. Keep existing threshold and lead-time preferences, unaired/availability rules, shuffle target selection, party controls, plugin variant continuation, silent transition and still-watching rules. A pure NextEpisodePreparationPolicy supplies eligibility/identity decisions to coroutine integration and is tested inside PlayerNextEpisodeRulesTest. A profile/target change or player release cancels the owned work and discards its banked result. Never pass prefetchOnly through a path that opens an episode picker, plays a cloud file, or changes visible playback state.
  - Acceptance: repeated progress ticks and both preload switches produce one search for one target; stopping before the threshold produces none; changing target discards the old result; no preparation chain runs into subsequent episodes; cloud/party/manual-selection exclusions have no hidden side effects.

## Gates

- [ ] **FG1 — Verify integrated Android behaviour and service request budgets.**
  - Depends on: FP1, FP2, FP3, FP4, FP5.
  - Scope: app/src/test/java/com/nuvio/tv/core/debrid/LocalDebridAvailabilityServiceTest.kt, app/src/test/java/com/nuvio/tv/core/debrid/DirectDebridStreamPreparerTest.kt, app/src/test/java/com/nuvio/tv/core/debrid/TorboxDirectDebridResolverTest.kt, app/src/test/java/com/nuvio/tv/data/repository/StreamRepositoryPluginIsolationTest.kt, app/src/test/java/com/nuvio/tv/data/repository/StreamSearchSessionCacheTest.kt, app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerNextEpisodeRulesTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
    - `bash scripts/check-torbox-instant.sh` (cwd: ., timeout: 1m)
  - Add cross-boundary regression coverage to the existing affected test classes using fake APIs/repositories and virtual time: browse ten title pages means zero stream/preparation calls; explicit Play produces one shared search; repeated/overlapping hashes reuse availability calls; source updates create at most one prepared link; immediate-next-episode paths share one target/search; stale-link refresh does not scrape again. Assert account/profile separation and no auto-download of uncached files. Test the integrated code paths rather than standalone replicas of the intended rules. Gate scope covers the regression files; any production repair uses the queue scope mechanism for the affected task files. Record exact request-count evidence in the commit body. No public service endpoints or live account are used.
  - Acceptance: full JVM suite and TorBox preservation guard pass; request-count regressions fail deterministically when browsing or duplicates add network work.

- [ ] **FG2 — Verify the full-flavor APK still builds.**
  - Depends on: FG1.
  - Scope: app/build.gradle.kts
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:assembleFullDebug --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs FG1’s passing integrated behaviour and request-count regressions before packaging that same revision. Build the integrated full flavor with the existing SDK and standard Android debug signing selected by -PciUseDebugSigning=true. Regular builds keep their existing signing configuration. Retain the ABI splits and universal APK, updater routing, TorBox integration and both audio controls. Do not install or publish the APK and do not change signing material. Report the resulting APK paths and commit revision in the commit body.
  - Acceptance: fullDebug APK assembly succeeds on the integrated revision.

## Addition 2026-11-21: Debrid-Stream-style instant playback with load toggles

Do the slow work before Play, the way Debrid Stream does. Opening a movie page searches sources and prepares the top source. A series page does the same for the episode its Play button targets (resume, next up, or the pre-picked random shuffle episode). Preparing means resolving the playable link, reading the file's first bytes and its Matroska index, and caching the frame-rate result. Parallel downloading becomes the default, and Matroska seeks resync to the next cluster. Every behaviour that adds requests to public add-ons sits behind a settings toggle. Browsing home rows and focusing posters still causes zero requests.

### Addition fixed decisions

| Decision | Value |
|---|---|
| Triggers | Only: (a) a movie details page, (b) a series details page's hero Play target as computed by `resolveHeroPlaybackVideo` (resume, next up, or pre-picked shuffle episode; a "Roll again" re-target replaces it), (c) the existing FP5 next-episode preparation in the player. Never home rows, poster focus, search results, or episode-list focus. Continue Watching focus is allowed only through the "Addition 2026-11-21b" rules (CS tasks). |
| Dwell | A details-page request starts only after 1,000 ms of the same target staying current. Leaving the page or changing target sooner means zero requests. |
| Settings (per profile, in `PlayerSettings`) | `prefetchMovieOnDetails` (key `prefetch_movie_on_details`, default true); `prefetchEpisodeOnDetails` (key `prefetch_episode_on_details`, default true); `prefetchPrepareTopSource` (key `prefetch_prepare_top_source`, default true). All off = today's request pattern exactly. |
| Speculative search budget | At most 20 speculative searches per rolling 60 minutes per app process. A target whose search is already held by `StreamSearchSessionCache` (`getCachedStreamsFromAllAddons` returns non-null) is not searched again and does not count. Explicit Play is never limited. |
| Search identity | The speculative search calls `StreamRepository.getStreamsFromAllAddons` with exactly the arguments `StreamScreenViewModel` would pass for the same Play: type, videoId, season, episode, `forceRefresh = false`, and `priorityBingeGroup` from the shared helper `StreamSearchBingeGroup.resolve`. Pressing Play then joins the same cached session, so there is no second search. The coordinator stops waiting after 60,000 ms; the session itself follows its existing 15-minute rules. |
| Top-source choice | The same `StreamAutoPlaySelector.selectAutoPlayStream` call `StreamScreenViewModel` makes once all sources have loaded. If that returns null, use the first stream in `StreamAutoPlaySelector.orderAddonStreams` order that is directly playable: an http(s) non-torrent URL, or a torrent whose `debridCacheStatus.state` is `CACHED`. Never CHECKING/UNKNOWN/NOT_CACHED torrents, magnets, p2p, loopback, HLS (`.m3u8`) or DASH (`.mpd`). |
| Top-source budget | One preparation per target. At most 20 link warm-ups per rolling 60 minutes per process. TorBox preparation also keeps the existing 6/minute and 30/hour budgets through `DirectDebridStreamPreparer`, called with `minimumLimit = 1`. |
| Warm-up reads | Use `PlayerPlaybackNetworking.playbackHttpClient`, following redirects. Head: `Range: bytes=0-262143` (262,144 bytes, matching ParallelRangeDataSource's bootstrap read), stored with `PrefetchWindowStore.putHead` under the final URL. Tail: only for Matroska (first four bytes `1A 45 DF A3`) with total length from `Content-Range` above 16 MiB, the last `PrefetchWindowStore.TAIL_WINDOW_BYTES` bytes stored with `putTail`. A non-206 response stores nothing and substitutes nothing. |
| Warm store | `PlaybackWarmupStore`: memory only, 16 entries, TTL 10 minutes. Key: original URL plus a SHA-256 fingerprint of the sorted request headers. Value: final URL, total length, created time. Never persisted or logged. |
| Frame rate | When `frameRateMatchingMode != OFF`, the warm-up calls `FrameRateUtils.detectFrameRateWithOkHttpProbe` on the final URL with the same headers and filename, then `FrameRateUtils.cacheFrameRate(finalUrl, headers, detection, filename)`. That way `runAfrPreflightIfEnabled` gets a cache hit. |
| Player substitution | ExoPlayer media source creation and the AFR preflight use `PlaybackWarmupStore.effectiveUrl(original, headers)`. `currentStreamUrl`, progress, scrobble and source identity keep the original URL. MPV keeps the original URL. Before the first frame, a substituted URL failing with HTTP 401/403/404/410 or a connection/timeout error invalidates its entry, and the existing auto-retry then reopens the original URL. At most one fallback per playback. |
| Fast streaming defaults | One-time per-profile migration (key `migration_fast_streaming_defaults_done`): `parallel_network_enabled = true`, `use_parallel_connections = true`, connection count 4, chunk size 4,096 KB (remove the legacy MB key). New-profile defaults match. The user can still turn them off afterwards. |
| Transport | Playback `OkHttpClient`s get `ConnectionPool(8, 5, MINUTES)`. ParallelRangeDataSource remembers a per-host connection limit process-wide: a 429/503 rate-limit episode lowers that host's limit by one (minimum 1) for 30 minutes, then it returns to the configured count. |
| MKV resync | Wrap every Matroska extractor created by `DolbyVisionExtractorsFactory`. After `seek(position > 0, …)` once a `SeekMap` has been output, the next `read` peeks up to 1,048,576 bytes for the Cluster ID `1F 43 B6 75` and skips to it. If it isn't found, nothing is skipped. |
| Privacy | No URLs, headers, keys, hashes, magnets or titles in new logs. Profile switches cancel pending work and the stores are keyed so another profile's headers or account never match. |
| Tests | JVM only, using fakes, MockWebServer and virtual time. No public add-on, debrid or CDN traffic. |

### Tasks

- [ ] **IP1 — Add the instant-playback settings and the fast-streaming defaults migration.**
  - Depends on: none.
  - Priority: 150
  - Scope: app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt, app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackInstantPlaybackSettings.kt, app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt, app/src/main/res/values/strings.xml, app/src/test/java/com/nuvio/tv/data/local/PlayerSettingsInstantPlaybackTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*PlayerSettingsTimeoutMigrationTest' --tests '*PlayerSettingsInstantPlaybackTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Read `PlayerSettings` and `PlayerSettingsDataStore` (keys around line 720, the settings mapping around line 1150, `migrateProfile` around line 784, the `setUseParallelConnections`/`setParallelNetworkEnabled`/`setParallelConnectionCount`/`setParallelChunkSizeKb` setters).
  - Add the three `PlayerSettings` booleans and keys from Addition fixed decisions (default true), plus suspend setters `setPrefetchMovieOnDetails`, `setPrefetchEpisodeOnDetails`, `setPrefetchPrepareTopSource`.
  - Add the one-time migration inside `migrateProfile` using the existing `migration…DoneKey` pattern. Put the decision in a pure companion function `PlayerSettings.fastStreamingDefaults(): FastStreamingDefaults` (data class with the four values) that the migration writes, so tests can assert it. Change `DEFAULT_USE_PARALLEL_CONNECTIONS` to true, `DEFAULT_PARALLEL_CONNECTION_COUNT` to 4, `DEFAULT_PARALLEL_CHUNK_SIZE_KB` to 4096, and the `parallelNetworkEnabled` read default to true. Change the legacy-MB fallback (`?: 16`) to `?: 4`. Keep min/max bounds, native-memory caps and every other migration unchanged.
  - Add a composable `InstantPlaybackControls(settings, onUpdate)` in the new file, using `SettingsToggleRow` like `ParallelNetworkControls`. Show it at the top of the existing Stream Selection section (`PlaybackStreamSelectionSection` in PlaybackAutoPlaySettings.kt). Do not add a `PlaybackSection` enum value; `SettingsStructureTest` lists them. Strings (English only, in values/strings.xml):
    - title "Instant playback"
    - "Find sources when I open a movie"
    - "Find sources for the episode I'm up to"
    - "Get the top source ready"
    - note: "Each title you open sends the same source search Play would, at most 20 an hour, and only after you stay on the page for a second. Turn these off to search only when you press Play."
  - Acceptance: fresh and migrated profiles read the three toggles as true and parallel downloading as on with 4 connections and 4 MB pieces. A profile that later turns parallel off stays off (the migration runs once). Tests cover the pure defaults, the migration-runs-once rule using the same pure-function approach as `PlayerSettingsTimeoutMigrationTest`, and the new defaults.

- [ ] **IP2 — Warm a chosen source: final URL, header and index bytes, frame rate.**
  - Depends on: none.
  - Priority: 150
  - Scope: app/src/main/java/com/nuvio/tv/core/streams/PlaybackLinkWarmup.kt, app/src/main/java/com/nuvio/tv/core/streams/PlaybackWarmupStore.kt, app/src/test/java/com/nuvio/tv/core/streams/PlaybackLinkWarmupTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*ParallelRangeDataSourceTest' --tests '*PlaybackLinkWarmupTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Read `PrefetchWindowStore` and `ParallelRangeDataSource.BootstrapCacheEntry`, including how `open()` builds the entry it passes to `updateBootstrapCache` (around line 776). Also read `PlayerPlaybackNetworking.playbackHttpClient`, `FrameRateUtils.detectFrameRateWithOkHttpProbe`/`cacheFrameRate`/`getCachedFrameRate`, and `runAfrPreflightIfEnabled` (which headers and filename it passes).
  - Create `PlaybackWarmupStore` (object, with an internal `nowMs` override for tests): `put(originalUrl, headers, entry)`, `effectiveUrl(originalUrl, headers): String` (returns the original when absent or expired), `invalidate(originalUrl, headers)`, and `clear()`. Limits and key are in Addition fixed decisions.
  - Create `@Singleton class PlaybackLinkWarmup @Inject constructor(@ApplicationContext context)` with `suspend fun warm(url: String, headers: Map<String,String>, filename: String?, mimeType: String?, frameRateMatchingEnabled: Boolean): Boolean`, running on `Dispatchers.IO`. It skips the excluded URL kinds. It reads the head range with the given headers; on a 206 it stores the warm entry plus a head `BootstrapCacheEntry` keyed by the final URL (built the same way `open()` builds it). For a large Matroska file it fetches and stores the tail window, then the frame-rate cache entry when matching is enabled. It returns true only when the warm entry was stored. Close every response body and respect coroutine cancellation (pass `isCancelled` to the frame-rate probe).
  - Never log URLs or headers.
  - Acceptance (MockWebServer):
    - a 302 then 206 MKV stores the final URL, head and tail;
    - an MP4 stores the head but no tail;
    - a 200 response stores nothing;
    - an expired entry returns the original URL;
    - different headers do not share an entry;
    - cancellation stops further requests.

- [ ] **IP3 — Add the prefetch coordinator with dwell, budgets and top-source preparation.**
  - Depends on: IP1, IP2.
  - Priority: 150
  - Scope: app/src/main/java/com/nuvio/tv/core/streams/PlaybackPrefetchCoordinator.kt, app/src/main/java/com/nuvio/tv/core/streams/PrefetchTarget.kt, app/src/main/java/com/nuvio/tv/core/streams/StreamSearchBingeGroup.kt, app/src/main/java/com/nuvio/tv/core/debrid/DirectDebridStreamPreparer.kt, app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreenViewModel.kt, app/src/test/java/com/nuvio/tv/core/streams/PlaybackPrefetchCoordinatorTest.kt, app/src/test/java/com/nuvio/tv/core/debrid/DirectDebridStreamPreparerTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*DirectDebridStreamPreparerTest' --tests '*PlaybackPrefetchCoordinatorTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs IP1's toggles and IP2's `PlaybackLinkWarmup`.
  - `PrefetchTarget(profileId, type, videoId, contentId, season, episode, kind: MOVIE_DETAILS | EPISODE_DETAILS | NEXT_EPISODE)`.
  - Move the `persistedBingeGroup` computation from `StreamScreenViewModel` (around line 536) into `StreamSearchBingeGroup.resolve(playerSettings, bingeGroupCacheDataStore, contentId): String?` and call it from both places, with no behaviour change in `StreamScreenViewModel`.
  - Add `minimumLimit: Int = 0` to `DirectDebridStreamPreparer.prepare`: effective limit = max(setting, minimumLimit). `canResolvePlayableLinks` and the background budgets still apply. Existing callers are unchanged.
  - `@Singleton PlaybackPrefetchCoordinator @Inject constructor(...)` with internal test parameters for scope/dispatcher/clock, following `StreamSearchSessionCache`. Methods:
    - `fun request(target)`: replaces any pending target;
    - `fun cancelPending(target)`;
    - `fun onProfileChanged()`.

    Flow:
    1. Toggles: MOVIE_DETAILS needs `prefetchMovieOnDetails`; EPISODE_DETAILS needs `prefetchEpisodeOnDetails`. NEXT_EPISODE is not handled here.
    2. Dwell.
    3. Cached-session check, then the search budget.
    4. Collect the search to completion or 60 s.
    5. If `prefetchPrepareTopSource` is on, pick the top source per Addition fixed decisions. For a CACHED debrid torrent, call `prepare(listOf(candidate), …, minimumLimit = 1)` and warm the prepared stream's URL. Otherwise warm the candidate URL directly, within the warm-up budget. Pass `stream.behaviorHints?.proxyHeaders?.request ?: emptyMap()` as headers (the same source `StreamScreenViewModel` uses around line 1562 when it builds the playback request), the stream's `behaviorHints` filename, and `frameRateMatchingMode != OFF`.

    A newer target cancels the older target's preparation but not its search session. A profile change cancels everything.
  - Acceptance (virtual time, fake repository/preparer/warm-up):
    - leaving before 1 s means zero searches;
    - toggles off means zero searches and zero warm-ups;
    - a target already in the session cache means no new search;
    - 21 distinct targets in an hour give 20 searches;
    - one target gives exactly one search and at most one preparation;
    - an ineligible top source means zero warm-ups;
    - a TorBox candidate uses `minimumLimit = 1`;
    - a profile change cancels.

- [ ] **IP4 — Trigger prefetch from the movie and series details pages.**
  - Depends on: IP3.
  - Scope: app/src/main/java/com/nuvio/tv/ui/screens/detail/MetaDetailsViewModel.kt, app/src/main/java/com/nuvio/tv/ui/screens/detail/DetailsPrefetchTarget.kt, app/src/test/java/com/nuvio/tv/ui/screens/detail/DetailsPrefetchTargetTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*DetailEpisodeShuffleTest' --tests '*DetailsPrefetchTargetTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs IP3's coordinator API.
  - Add a pure `detailsPrefetchTarget(profileId, meta, nextToWatch, episodesForSeason, settings): PrefetchTarget?` mirroring the hero Play callback in `MetaDetailsScreen` (`heroPlayClick`):
    - when `resolveHeroPlaybackVideo` returns a video: EPISODE_DETAILS with `video.id`, `meta.apiType`, `meta.id`, season, episode;
    - otherwise MOVIE_DETAILS with `meta.id`;
    - null when `PlaybackAvailability.canStream` would refuse it or the matching toggle is off.
  - In `MetaDetailsViewModel`, after meta loads and whenever `nextToWatch` or the selected season changes (including shuffle "Roll again"), call `coordinator.request(target)` when the target changed. In `onCleared`, call `cancelPending`.
  - Do not change navigation, the Play button or episode-list focus handling.
  - Acceptance: tests cover:
    - movie target;
    - resume episode;
    - next-up episode;
    - pre-picked shuffle episode;
    - a "Roll again" change producing a new target;
    - an unavailable title or a toggle off producing null.

- [ ] **IP5 — Use warmed links in ExoPlayer and the frame-rate preflight, with one fallback.**
  - Depends on: IP2.
  - Scope: app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerAfrPreflight.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/WarmUrlFallbackPolicy.kt, app/src/test/java/com/nuvio/tv/ui/screens/player/WarmUrlFallbackPolicyTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*PlayerFirstFrameCodecRecoveryPolicyTest' --tests '*WarmUrlFallbackPolicyTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs IP2's `PlaybackWarmupStore`.
  - In `PlayerMediaSourceFactory`, map the incoming URL through `PlaybackWarmupStore.effectiveUrl(url, headers)` for progressive http(s) ExoPlayer sources only (not HLS/DASH, loopback, torrent-local or MPV). Use the effective URL as the MediaItem URI, so ParallelRangeDataSource's `PrefetchWindowStore` head/tail lookups (keyed by `dataSpec.uri`) match the entries IP2 stored. Keep `onStreamRedirected` reporting relative to the original.
  - In `runAfrPreflightIfEnabled`, use the effective URL for `getCachedFrameRate`, the probes and `cacheFrameRate`.
  - Add a pure `WarmUrlFallbackPolicy.shouldFallBack(error, wasSubstituted, firstFrameRendered, alreadyFellBack): Boolean` per Addition fixed decisions. In the error-recovery path, when it returns true, call `PlaybackWarmupStore.invalidate` before the existing auto-retry, so the retry opens the original URL. It must not consume an extra retry slot or trigger a new source search.
  - Acceptance: tests cover substituted 403/404/410/401 and timeouts before the first frame (fall back), after the first frame (no), unsubstituted (no), and a second failure (no).

- [ ] **IP6 — Per-host connection memory and a larger playback connection pool.**
  - Depends on: none.
  - Scope: app/src/main/java/com/nuvio/tv/ui/screens/player/ParallelRangeDataSource.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPlaybackNetworking.kt, app/src/test/java/com/nuvio/tv/ui/screens/player/ParallelRangeDataSourceTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*ParallelRangeDataSourceTest' --tests '*ParallelRangeChunkLeaseTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Read the rate-limit state (`noteRateLimitHit`, `beginRateLimitEpisode`, `currentAllowedDepth`, `downloadChunkWithRateLimitBackoff`) and how `parallelConnections` bounds concurrent downloads.
  - Add a process-wide `HostConnectionLimits` (internal object in the same file, with an internal clock for tests). It holds the per-host limit from Addition fixed decisions; a new session for a host starts at `min(configured, remembered)`. Keep existing hedging, stall restart, bootstrap and buffer pooling unchanged.
  - Set `ConnectionPool(8, 5, TimeUnit.MINUTES)` on both playback clients in `PlayerPlaybackNetworking`.
  - Acceptance: tests prove that a 429 episode lowers the next session for that host, a different host is unaffected, the limit never drops below 1, and it recovers after 30 minutes of fake time.

- [ ] **IP7 — Resync Matroska reads to the next cluster after a seek.**
  - Depends on: none.
  - Scope: app/src/main/java/com/nuvio/tv/core/player/MatroskaClusterResyncExtractor.kt, app/src/main/java/com/nuvio/tv/core/player/DolbyVisionExtractorsFactory.kt, app/src/test/java/com/nuvio/tv/core/player/dvmkv/MatroskaClusterResyncExtractorTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*MatroskaNestedSeekHeadTest' --tests '*MatroskaClusterResyncExtractorTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Read `DolbyVisionExtractorsFactory.wrap` and the fake `ExtractorInput` helpers used by `MatroskaNestedSeekHeadTest`.
  - Add `MatroskaClusterResyncExtractor(delegate: Extractor) : Extractor`. It delegates every method, and wraps the `ExtractorOutput` passed to `init` to note when `seekMap` was called. It implements the resync rule from Addition fixed decisions using `peekFully(…, allowEndOfInput = true)`, `resetPeekPosition` and `skipFully`, and returns `getUnderlyingImplementation()` from the delegate.
  - In `wrap`, wrap the `DvMatroskaExtractor` it returns.
  - Acceptance:
    - a seek landing 1,000 bytes before a cluster skips exactly those bytes;
    - landing on a cluster skips nothing;
    - no cluster within 1 MiB skips nothing;
    - a seek to position 0, or before a seek map exists, never scans;
    - existing Matroska tests still pass.

- [ ] **IP8 — Warm the prepared next episode in the player.**
  - Depends on: IP1, IP2.
  - Scope: app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerViewModel.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/NextEpisodePreparationPolicy.kt, app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerNextEpisodeRulesTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*PlayerNextEpisodeRulesTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs IP1's `prefetchPrepareTopSource` and IP2's `PlaybackLinkWarmup`.
  - Read FP5's single next-episode target lifecycle (`prefetchNextEpisodeStream`, the `directDebridStreamPreparer.prepare` call near line 1463, and `NextEpisodePreparationPolicy`). Inject `PlaybackLinkWarmup` through `PlayerViewModel` into `PlayerRuntimeController`.
  - When that lifecycle has chosen its next-episode stream (prepared TorBox link, or a directly playable http(s) URL) and `prefetchPrepareTopSource` is on, call `warm(...)` once for that target, from the same owned coroutine, so a target change or player release cancels it.
  - Add a pure `NextEpisodePreparationPolicy.shouldWarm(stream, settings, alreadyWarmed): Boolean` and test it in `PlayerNextEpisodeRulesTest`. This also covers Shuffle Play's random next episode, which uses the same target.
  - Do not change thresholds, the search count or visible playback.
  - Acceptance: one warm-up per next-episode target; none when the toggle is off, the stream is ineligible or the target was already warmed; a target change cancels it.

### Addition gates

- [ ] **IG1 — Verify integrated behaviour and public add-on request budgets.**
  - Depends on: IP1, IP2, IP3, IP4, IP5, IP6, IP7, IP8.
  - Scope: app/src/test/java/com/nuvio/tv/core/streams/PrefetchLoadBudgetRegressionTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 20m)
    - `bash scripts/check-torbox-instant.sh` (cwd: ., timeout: 1m)
  - Needs IP1's settings, IP2's warm-up, IP3's coordinator, IP4's details trigger, IP5's player substitution, IP6's transport limits all merged. Needs IP7's MKV wrapper merged so the full suite covers it. Needs IP8's next-episode warm-up merged so the full suite covers it.
  - Add `PrefetchLoadBudgetRegressionTest`, using the real `PlaybackPrefetchCoordinator`, `StreamSearchSessionCache`-backed fake repository, `detailsPrefetchTarget` and virtual time. It must show:
    - with all toggles off, opening ten details pages makes zero add-on searches and zero warm-ups;
    - with toggles on, ten pages each left before 1 s make zero;
    - one page held for 2 s, then Play through the same search key, makes exactly one search;
    - 25 dwelled pages in an hour make 20 searches;
    - a series page whose shuffle target is re-rolled three times within 1 s makes one search, for the final target only.

    Record the counts in the commit body. Production repairs go through the queue scope mechanism for the owning task's files.
  - Acceptance: the full JVM suite and the TorBox preservation guard pass, and the request-count regressions fail deterministically if a change adds browse-time or duplicate searches.

- [ ] **IG2 — Verify the full-flavor APK builds with the addition.**
  - Depends on: IG1.
  - Scope: app/build.gradle.kts
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:assembleFullDebug --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs IG1's passing integrated revision. Build exactly as FG2 does, with the same signing and ABI rules, and no install or publish. Report the APK paths and revision in the commit body.
  - Acceptance: fullDebug assembly succeeds on the integrated revision.

## Addition 2026-11-21b: Cold-start parity (Continue Watching, early autoplay)

Close the remaining gap with Debrid Stream for Play pressed without first dwelling on a details page. Autoplay stops waiting for slow add-ons once the answer cannot change (or, with a toggle, after 3 seconds). Continue Watching resumes from the last working link with no add-on search, falls back to one normal search when that link has died, and warms the focused Continue Watching item. Debrid Stream's own torrent scraper is deliberately not copied: it moves load onto public torrent indexers.

### Addition 2026-11-21b fixed decisions

| Decision | Value |
|---|---|
| New settings (per profile, `PlayerSettings`) | `autoPlayFastStart` (key `autoplay_fast_start`, default true); `prefetchContinueWatching` (key `prefetch_continue_watching`, default true). Shown in IP1's `InstantPlaybackControls`. |
| Reuse-last-link default | One-time per-profile migration (key `migration_reuse_last_link_default_done`) sets `stream_reuse_last_link_enabled = true`. It does not touch `stream_reuse_last_link_cache_hours` (read default 24). New profiles read true. The user can turn it off afterwards. |
| Exact early decision | In the direct autoplay flow (mode not MANUAL), and with no persisted binge group, autoplay decides as soon as `StreamAutoPlaySelector.selectAutoPlayStream` over the answered sources returns a stream whose source is at index k in `orderAddonStreams` order and every source with index < k has finished (answered, failed or timed out). This picks the same stream as waiting for everything. With a persisted binge group, the existing binge-only early path is unchanged. |
| Fast start | When `autoPlayFastStart` is on and no exact decision exists 3,000 ms after the search started, decide with the current best `selectAutoPlayStream` result over the answered sources. CHECKING/UNKNOWN/NOT_CACHED torrents never qualify. If nothing qualifies, keep the existing timeout behaviour. |
| Search progress | `StreamRepository.searchProgress(type, videoId, season, episode): StreamSearchProgress?` with `requestedSources: List<String>` (in the order `orderAddonStreams` uses) and `finishedSources: Set<String>`, read from the shared search session. Returns null when no session exists. No extra network work. |
| Dead reused link | Before the first frame, a playback started from a reused cached link that fails with HTTP 401/403/404/410, a connection error or a timeout removes that `StreamLinkCacheDataStore` entry. It then runs one source search through the player's existing `loadSourceStreams` path and plays the `selectAutoPlayStream` choice. At most once per playback. FP4's TorBox re-resolve runs first when it applies. |
| Continue Watching focus | A Continue Watching card focused for 1,000 ms becomes the target (an `InProgress` item's own video, or a `NextUp` item's next episode). If reuse is on and a valid cached link exists for its content key (`"${type.lowercase()}|$videoId"`, the `StreamScreenViewModel.streamCacheKey` format), warm that link through `PlaybackLinkWarmup` (debrid/CDN only, no add-on request, within IP3's warm-up budget). Otherwise, when `prefetchContinueWatching` is on, call `PlaybackPrefetchCoordinator.request` with kind `CONTINUE_WATCHING` (IP3's search and warm-up budgets apply). Leaving the row or moving focus cancels the pending target. |
| Not copied | Own torrent scraping, CPU affinity, custom allocator, and Debrid Stream's LoadControl. Nuvio's 1 s start buffer is already lower than Debrid Stream's 3 s. |

### Tasks

- [ ] **CS1 — Add the cold-start settings and the reuse-last-link default migration.**
  - Depends on: IP1.
  - Priority: 150
  - Scope: app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt, app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackInstantPlaybackSettings.kt, app/src/main/res/values/strings.xml, app/src/test/java/com/nuvio/tv/data/local/PlayerSettingsInstantPlaybackTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*PlayerSettingsTimeoutMigrationTest' --tests '*PlayerSettingsInstantPlaybackTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs IP1's `InstantPlaybackControls`, toggles and migration pattern.
  - Add `autoPlayFastStart` and `prefetchContinueWatching` with keys and defaults from the 2026-11-21b fixed decisions, plus suspend setters `setAutoPlayFastStart` and `setPrefetchContinueWatching`. Change the `stream_reuse_last_link_enabled` read default from false to true and add the one-time migration inside `migrateProfile` with a pure companion function the test can assert, as IP1 did.
  - Add two rows to `InstantPlaybackControls` with `SettingsToggleRow`. Strings (English only):
    - "Don't wait for slow add-ons" — subtitle "Start the best source found after 3 seconds."
    - "Find sources for the Continue Watching item I'm on" — subtitle "Resumes use your last working link first and search only if there isn't one."
  - Acceptance: fresh and migrated profiles read reuse-last-link on with its stored or 24-hour cache, and both new toggles on. A profile that turns reuse off afterwards stays off (the migration runs once). Tests extend `PlayerSettingsInstantPlaybackTest`.

- [ ] **CS2 — Start autoplay as soon as the answer cannot change, or after 3 seconds.**
  - Depends on: CS1, IP3.
  - Scope: app/src/main/java/com/nuvio/tv/core/player/AutoPlayEarlyDecision.kt, app/src/main/java/com/nuvio/tv/domain/repository/StreamRepository.kt, app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt, app/src/main/java/com/nuvio/tv/data/repository/StreamSearchSessionCache.kt, app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreenViewModel.kt, app/src/test/java/com/nuvio/tv/core/player/AutoPlayEarlyDecisionTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*StreamSearchSessionCacheTest' --tests '*AutoPlayEarlyDecisionTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs CS1's `autoPlayFastStart` and IP3's `StreamScreenViewModel` changes.
  - Read the direct autoplay collection in `StreamScreenViewModel` (the `streamLoadInner` collector, `timeoutElapsed`, `resolvedAutoPlayTarget`, the binge-only early path) and how `StreamRepositoryImpl.getStreamsFromAllAddons` and `StreamSearchSessionCache` track each source's request.
  - Add `searchProgress` per the 2026-11-21b fixed decisions. Every `StreamRepository` implementation and test fake in the tree must compile; fakes may return null.
  - Add a pure `AutoPlayEarlyDecision.decide(orderedSources: List<String>, finishedSources: Set<String>, candidate: Stream?, elapsedMs: Long, fastStart: Boolean): Boolean` implementing the exact rule and the fast-start rule. Call it on each emission and once at 3,000 ms (a `delay` in the same search coroutine). When it returns true, take the existing early-match path (`resolvedAutoPlayTarget = true`, `autoSelectTriggered = true`, `autoPlayStream = candidate`). The candidate is the same `selectAutoPlayStream` call the timeout path makes, over the answered streams.
  - Do not change MANUAL mode, the binge-group early path, reuse-last-link, or the timeout fallback.
  - Acceptance (pure tests): the first source answering with a match decides immediately; a match from source 2 waits for source 1; a failed source 1 counts as finished; fast start decides at 3 s with a match and not without one; fast start off waits; CHECKING torrents never decide.

- [ ] **CS3 — Resume Continue Watching from the last link, with one search if it died.**
  - Depends on: CS1, IP5, IP8.
  - Scope: app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt, app/src/main/java/com/nuvio/tv/ui/screens/player/ReusedLinkFallbackPolicy.kt, app/src/main/java/com/nuvio/tv/data/local/StreamLinkCacheDataStore.kt, app/src/test/java/com/nuvio/tv/ui/screens/player/ReusedLinkFallbackPolicyTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*PlayerFirstFrameCodecRecoveryPolicyTest' --tests '*ReusedLinkFallbackPolicyTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs CS1's default, IP5's error-recovery changes and IP8's `PlayerRuntimeControllerStreams` changes.
  - Read how the player learns that playback came from a reused cached link (the `StreamPlaybackInfo` that `StreamScreenViewModel` builds from `streamLinkCacheDataStore.getValid`; carry a `fromReusedLink` flag through the existing navigation arguments if none exists). Read `attemptStartupRecovery`, FP4's TorBox re-resolve, IP5's `WarmUrlFallbackPolicy`, and `loadSourceStreams`.
  - Add `StreamLinkCacheDataStore.remove(contentKey)`.
  - Add a pure `ReusedLinkFallbackPolicy.shouldSearchAgain(error, fromReusedLink, firstFrameRendered, alreadySearched, torboxRefreshPending): Boolean` per the 2026-11-21b fixed decisions. When it returns true, remove the entry and run one `loadSourceStreams`-based search for the current video. Play its `selectAutoPlayStream` choice; with no choice, open the sources panel.
  - Do not change behaviour for playback that did not come from a reused link.
  - Acceptance (pure tests): 403/404/410/401/timeout before the first frame on a reused link searches once; after the first frame, no; not reused, no; a second failure, no; TorBox refresh pending defers to FP4.

- [ ] **CS4 — Warm the focused Continue Watching item.**
  - Depends on: CS1, IP3.
  - Scope: app/src/main/java/com/nuvio/tv/core/streams/PlaybackPrefetchCoordinator.kt, app/src/main/java/com/nuvio/tv/core/streams/PrefetchTarget.kt, app/src/main/java/com/nuvio/tv/ui/screens/home/ContinueWatchingPrefetchTarget.kt, app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModel.kt, app/src/main/java/com/nuvio/tv/ui/screens/home/HomeScreen.kt, app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt, app/src/main/java/com/nuvio/tv/ui/screens/home/ClassicHomeContent.kt, app/src/test/java/com/nuvio/tv/core/streams/PlaybackPrefetchCoordinatorTest.kt, app/src/test/java/com/nuvio/tv/ui/screens/home/ContinueWatchingPrefetchTargetTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*PlaybackPrefetchCoordinatorTest' --tests '*ContinueWatchingPrefetchTargetTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs CS1's `prefetchContinueWatching` and IP3's coordinator.
  - Add `CONTINUE_WATCHING` to the `PrefetchTarget` kind, gated by `prefetchContinueWatching`. Add `fun warmCachedLink(target, url, headers, filename)` to the coordinator: dwell plus warm-up budget, and no search.
  - Add a pure `continueWatchingPrefetchTarget(profileId, item: ContinueWatchingItem): PrefetchTarget?` covering `InProgress` and `NextUp`, using the same type/videoId/season/episode the Continue Watching click handler in `HomeScreen` passes to the stream screen.
  - Add an `onContinueWatchingFocused(item)` callback, threaded from every home layout that renders Continue Watching cards (search `ContinueWatchingItem` under ui/screens/home; Modern and Classic at least), into `HomeViewModel`. The view model reads the reuse-link cache for the content key and calls `warmCachedLink` or `request` per the 2026-11-21b fixed decisions. Losing focus or leaving home calls `cancelPending`. Poster focus in other rows stays inert.
  - Acceptance: tests show a 1 s dwell on a card with a cached link warms once and searches zero times; without a cached link it searches once (toggle on) or zero times (toggle off); leaving before 1 s does nothing; a NextUp item targets the next episode.

### Addition 2026-11-21b gates

- [ ] **CG1 — Verify cold-start behaviour, request budgets and the full suite.**
  - Depends on: CS1, CS2, CS3, CS4, IG2.
  - Scope: app/src/test/java/com/nuvio/tv/core/streams/PrefetchLoadBudgetRegressionTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 20m)
    - `bash scripts/check-torbox-instant.sh` (cwd: ., timeout: 1m)
  - Needs CS1's settings and CS4's Continue Watching trigger for the new regression cases. Needs CS2's early autoplay decision merged so the full suite covers it. Needs CS3's reused-link fallback merged so the full suite covers it. Needs IG2's verified revision underneath.
  - Extend `PrefetchLoadBudgetRegressionTest`: scrolling across ten Continue Watching cards for under 1 s each makes zero searches and zero warm-ups; a card with a cached link held for 2 s makes zero searches and one warm-up; with `prefetchContinueWatching` off, a card without a link makes zero searches. Record the counts in the commit body.
  - Acceptance: the full JVM suite and the TorBox preservation guard pass.

- [ ] **CG2 — Verify the full-flavor APK builds with cold-start parity.**
  - Depends on: CG1.
  - Scope: app/build.gradle.kts
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:assembleFullDebug --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs CG1's passing revision. Build exactly as IG2 does; no install or publish.
  - Acceptance: fullDebug assembly succeeds on the integrated revision.

Final verification: `bash scripts/check-torbox-instant.sh`

## Limits

- Real playback latency, frame pacing, audio output and sustained streaming on the Google TV Streamer are not proven by VM-based automated tests; no speed multiplier is promised.
- Per-addon timing values with the installed private configuration remain unverified on a real TV; the previous plan's device-only observation is retained here rather than assigned to a worker.
- The supplied APK is inspected statically for behavioural ideas; its proprietary source and binaries are not added to this repository.
- Addition 2026-11-21: real time-to-first-frame and seek latency against Debrid Stream are not measured; tests prove request counts and code paths only.
- Addition 2026-11-21: whether a given add-on's resolve URL may be fetched ahead without side effects (for example a debrid "add torrent" it triggers) is not provable offline; the "Get the top source ready" toggle is the control.
- Verification APKs use the standard Android debug key and cannot replace the installed release build. Signed release publication is outside this plan; the existing release workflow and production configuration remain unchanged.
