# Faster playback with bounded service load

Improve playback after an explicit Play action and prepare only the immediate next episode near its playback threshold. Browsing home rows, focusing posters, and opening title pages must cause zero new stream searches, TorBox additions, playback-link requests, or video downloads. Build on the upstream merge at `9d333ee9e` and its compilation repairs at `8c35265a5`; do not apply `nuvio-fast-playback.zip` wholesale.

## Fixed decisions

| Decision | Value |
|---|---|
| Search trigger | Explicit Play/source-picker action, or one immediate-next-episode preparation near its threshold. No details-page or poster-focus searches. |
| Public infrastructure | Use installed sources and existing shared search sessions. No bundled community add-on URLs, background refresh loops, duplicate searches, new scrapers, or service-limit increases. |
| Background work | At most one active next-episode target per player session. No season-wide preparation, unrelated movies, speculative video reads, or downloads of uncached torrents. |
| Preparation | Existing preparation setting remains opt-in; zero remains off. Cap this workflow to one selected cached source per media search. Keep the existing six/minute and thirty/hour background budgets. Foreground Play remains usable after those budgets are exhausted. |
| Availability reuse | TorBox only: account-key fingerprint plus normalized hash; memory only; positive TTL 120 seconds, negative TTL 30 seconds; 2,048 entries; no caching of failures/unknown results. Reuse concurrent requests. |
| Availability scheduling | Publish source groups promptly with CHECKING markers. At most two availability jobs active per search, one shared TorBox HTTP batch active at a time; at most 100 distinct hashes per request. Emit updates through one serialized accumulator. |
| Privacy and isolation | Preserve profile, provider, account, source configuration, episode identity, plugin variant and binge group. No keys, full URLs, headers, magnets, hashes, or titles in new diagnostic output. |
| Next episode | Keep existing settings. Collapse overlapping upstream source preload and fork link preparation into one target lifecycle. Begin at the existing configured preparation threshold, never on browsing or for every episode in a season. |
| Recovery | Refresh a rejected cached direct link once on explicit playback; no new source search. Do not add the ZIP automatic release-changing watchdog in this batch. |
| Player | Preserve current buffer modes, memory budgets, MP4 handling, frame-rate matching, MPV, ExoPlayer, Dialogue Leveler and upstream audio night mode. No read-ahead wrapper or large-MP4 exclusion. |
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
  - Read LocalDebridService.checkCached/checkTorboxCached and LocalDebridAvailabilityServiceTest. Add a bounded memory cache and concurrent-request reuse inside LocalDebridService using the availability limits above. Cache successful per-hash answers; preserve the public nullable-map contract and Premiumize behaviour. Split fresh unknown hashes into batches of at most 100, serialize TorBox network batches with a coroutine mutex, and recheck the cache after acquiring admission so overlapping batches do not repeat work. Keep network calls outside the cache-state mutex. Use an injected internal monotonic clock seam with a production default for TTL tests, without changing Hilt construction requirements. Distinguish cached absence from unknown failure. Do not cache 401, 429, malformed responses or transport failures. An individual caller cancelling must not corrupt cache state or strand waiters. Credential fingerprint changes make prior answers inaccessible.
  - Acceptance: overlapping concurrent requests ask TorBox once per fresh hash; different accounts never share answers; TTL/entry limits hold; unknown results stay unknown; tests prove positive, negative, failure, expiry, overlap and cancellation cases without real HTTP calls.

- [ ] **FP2 — Show arriving sources without waiting behind unrelated TorBox checks.**
  - Depends on: FP1.
  - Priority: 150
  - Scope: app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt, app/src/test/java/com/nuvio/tv/data/repository/StreamRepositoryPluginIsolationTest.kt
  - Checks:
    - `./gradlew -PciUseDebugSigning=true :app:testFullDebugUnitTest --tests '*StreamRepositoryPluginIsolationTest' --no-daemon --max-workers=2 --build-cache --console=plain` (cwd: ., timeout: 15m)
  - Needs FP1's shared request reuse to keep availability scheduling from increasing redundant calls. Read fetchStreamsFromAllSources, mergePresentedResult, the server-source branch and StreamSearchSessionCache. Separate arrival of a source group from its availability result: publish CHECKING promptly, then publish its checked replacement. Use at most two active checking jobs, backpressure further checking jobs, and one event-consumer coroutine owning accumulatedResults and emitting snapshots. Do not mutate published lists or emit from sibling coroutines of a flow builder. Complete a search only after all producer and availability jobs finish. Failed checks retain UNKNOWN. Propagate cancellation, reject obsolete/profile-switched session results, and preserve source ordering, server streams, plugin continuation, filtering and error summaries. Do not perform network work merely to serve getCachedStreamsFromAllAddons.
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

Final verification: `bash scripts/check-torbox-instant.sh`

## Limits

- Real playback latency, frame pacing, audio output and sustained streaming on the Google TV Streamer are not proven by VM-based automated tests; no speed multiplier is promised.
- Per-addon timing values with the installed private configuration remain unverified on a real TV; the previous plan's device-only observation is retained here rather than assigned to a worker.
- The supplied APK is inspected statically for behavioural ideas; its proprietary source and binaries are not added to this repository.
- Verification APKs use the standard Android debug key and cannot replace the installed release build. Signed release publication is outside this plan; the existing release workflow and production configuration remain unchanged.
