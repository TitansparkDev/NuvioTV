# Debrid Stream APK review and Nuvio playback decisions

Reviewed on 2026-11-05. This is a static inspection of the supplied Fire TV APK, compared with this fork before and after merging upstream/dev at 1206736fd. No community scraper, TorBox account, or video endpoint was contacted during this inspection. No decompiled Debrid Stream source or binary was added to the fork.

## Evidence and limits

- Package: com.debridstream.tv; version 3.8.2; version code 164.
- APK SHA-256: 34961ddd9d4c87a34059305ec0424d9cdb215a610b0cc7eb0a662ff719c571be.
- Read all ZIP entries, its complete patch and all seven supplied new files. The new-file copies match the corresponding patch content. Seven JUnit tests are included; the other tests behind its claimed 24-test result are absent.
- Used JADX 1.5.6 from its official release, checking the downloaded SHA-256 against the release asset digest. The APK is obfuscated. Full decompilation was incomplete; targeted simple-mode decompilation recovered the relevant classes and coroutine bodies. Some methods have reconstruction warnings and goto-style output.
- Claims below refer to inspected paths, not a complete reconstruction of every app feature. Static inspection cannot prove actual startup time, network throughput, the default setting on every installation, or that one mechanism explains the perceived speed.
- Decompiled working files remain outside the repository under /tmp/nuvio-apk-review. References below use original APK class names, before JADX's case-collision renaming. The report records behaviour without reproducing their source.

## What the APK actually does

### Shares searches and availability results

P2.J is a shared cache with completed search entries and in-flight requests. Concurrent callers can await existing work. P2.h0 handles a search session and publishes accumulating results rather than waiting for every add-on to finish. It coalesces result notifications over a 120 millisecond interval. It also stores per-provider/account/hash availability answers, including absence, in a separate bounded cache. The inspected cache constructor allocates capacities of 48 search entries and 20,000 availability entries; these are implementation details, not recommended Nuvio settings.

Nuvio already has a 15-minute shared search-session cache, profile/source-configuration keys, and shared in-flight playback-link resolution. The useful missing piece is short-lived availability-answer reuse across repeated hashes returned by different add-ons. A smaller account-scoped cache is sufficient for this fork.

### Can start searching before playback

The movie and episode UI coroutine paths d3.e.invokeSuspend and d3.g.invokeSuspend call X2.N3.a with the media identity. X2.N3.a avoids restarting an equivalent active/populated search and delegates new work to X2.N3.g, which starts N2.r.k. The d3.r.a composable retains the trace name DetailsBackgroundScrapeEffect (AutoPlayFlow.kt:720) and installs the d3.e coroutine through LaunchedEffect. This directly confirms a title-details background search. Poster-focus behaviour outside that title page is not established.

The ZIP's details-page trigger would make every visited eligible title a potential source search. Merely highlighting a poster is not a trigger in the supplied ZIP code. If ten different title pages are visited and each has three compatible add-ons, that can mean thirty add-on stream requests before Play, plus TorBox availability work. Existing-result reuse can reduce this only when the requested title and configuration match an existing session.

This is extra discovery traffic, not necessarily ten downloaded films. The ZIP's early search does not explicitly start video downloads. Nevertheless it conflicts with the requested service-load constraint, so the Nuvio plan excludes browse/title-page searching entirely.

### Uses the ordinary TorBox resolution sequence

The inspected a.a helpers implement TorBox createTorrent, mylist and requestdl calls. The file-selection path obtains the torrent's file list and requests the chosen file's URL. Nuvio already performs the same essential three-step sequence. This APK does not demonstrate a secret single-request TorBox shortcut.

The inspected create helper sends magnet and allow_zip=false; it does not send add_only_if_cached. That helper alone does not establish whether an uncached torrent is reachable from every caller. Nuvio's explicit cached-only creation protection is retained.

The cache-check helper chunks hashes into groups of 100; its caller supplies a concurrency value of six to the shared mapping helper. The Nuvio plan uses smaller bounded concurrency and reuses overlapping work instead of copying this pressure onto services.

### Downloads ahead during an active stream

h3.d0 implements a DataSource with a memory ring buffer and a dedicated thread named PrefetchNetwork. It opens the connection, receives bytes on a worker, and serves reads from the buffer. Its read method detects an eight-second no-data interval. This is download-ahead for the opened stream, not proof of browsing-triggered movie downloads.

Nuvio already has optional parallel downloading and retained chunk sessions, plus a single-connection MP4 session even with parallel connections off. A second generic download-ahead layer is excluded until measurements establish that the remaining plain-download path is a real bottleneck.

### Changes when playback is allowed to start

h3.g0 extends DefaultLoadControl and overrides loading/start decisions. The inspected methods confirm a 60-second ahead limit, a 100-millisecond rebuffer threshold and a 5,000-millisecond subsequent non-rebuffer threshold. Its first-start threshold references the constant AUTH_API_INVALID_CREDENTIALS, corresponding to the ZIP's reported 3,000 milliseconds. The h3.k.a helper confirms the ZIP's 55% heap budget up to a 256 MiB heap and 65% above it, with a 50 MiB minimum. These values do not account for Nuvio's other memory consumers.

Nuvio's bundled standard engine exposes 1,000-millisecond startup and 2,000-millisecond rebuffer defaults. The fork also has separate custom and performance buffering paths. Raising the standard startup threshold is not an evidenced first-picture improvement. The proposed memory replacement also bypasses the fork's existing device budgeting and removes its retained rewind buffer. These changes are excluded.

### Recognises large MP4 files

N2.r.h checks for an MP4 filename and a size greater than 21,474,836,480 bytes (20 GiB), confirming that the ZIP's threshold exists in the APK. This predicate does not measure how quickly an individual file starts. The plan excludes the blanket filter because it can discard a suitable file and the supplied Nuvio implementation does not first establish that its remaining alternatives are playable.

### Abandons stuck starts

h3.Y1.run checks first-picture state and buffered duration, schedules another check after two seconds, and can ask another-link logic to continue. The inspected body includes a 12-second buffer-stagnation threshold and a 45-second no-picture threshold. It distinguishes a delivery-side 429 when presenting failure. The caller's initial scheduling and all alternate-source selection rules are not fully reconstructed here.

The ZIP's 12-second first check is therefore plausible, while its exact integration still needs Nuvio-specific safeguards. This batch uses a narrower recovery improvement: refresh the same stale prepared TorBox link once without another source search. Automatic replacement with a different release is excluded from this plan.

### Prepares the next episode once

PlayerViewModel.tryMarkUpNextPrefetchAttempted uses an atomic once-only marker reset by prepare. k3.v.invokeSuspend checks that marker before invoking next-episode preparation and handles a PrefetchedUpNextPlayback value. This supports bounded immediate-next-episode preparation; it does not justify preparing a whole season.

The merged Nuvio fork contains both upstream source preload and fork selected-link preparation. Their lifecycle should be unified so that enabling both joins one search and banks at most one prepared cached link.

## Decisions for this fork

PLAN.md contains five implementation tasks and two final gates:

1. Reuse TorBox availability answers and overlapping requests.
2. Publish arriving sources without blocking behind an unrelated availability check.
3. Prepare at most one eligible cached link for the current explicit search; keep zero/off and existing budgets.
4. Refresh one rejected prepared TorBox URL once, preserving the same file and account.
5. Unify preparation of the immediate next episode near its existing threshold.

No browsing-triggered searches, bundled community endpoints, uncached torrent downloads, speculative movie bytes, higher request budgets, season-wide preparation, replacement buffer engine, or blanket large-MP4 exclusion are introduced by the plan. Performance claims require subsequent device measurements.

The original PLAN.md device-only observation about real per-addon timings is retained under Limits in the new plan, outside executable work.
