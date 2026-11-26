# Engineering journal

The newest entry is at the bottom. Each entry records what was done, the decisions and why, how it was verified, and the next step. The aim is that a fresh session (or a reviewer) can pick up the thread from this file plus `CLAUDE.md`.

---

## 2026-11-25 — Session 1: research, plan, repo (M0)

### Done
- Read the brief and saved it verbatim to `private/BRIEF.md`. This copy is local only and gitignored, so the employer's brief isn't published when the repo goes public. The requirement checklist lives in `docs/PLAN.md` §1.
- Researched **DAT 1.1.0**, released 2026-11-20:
  - the official docs and `llms.txt`
  - the Android repo's CHANGELOG and `AGENTS.md`
  - Meta's Claude Code plugin skills
  - the CameraAccess and BirdSpotter samples
  - the GitHub issue tracker, for real-glasses failure modes

  Findings are in `docs/RESEARCH.md`.
- **Most useful findings:**
  - 1.1.0 adds `DeviceSession.pause()/resume()`, which is exactly the lifecycle the brief asks for.
  - Mock Device Kit (MDK) can use the phone camera as the glasses feed.
  - MDK has no glasses audio, but the DAT Speech mock can run live phone ASR.
  - Experimental features (Speech, Audio, Motion, Inputs, standalone Photo) can't be published.
  - Meta's own sample code documents behaviour the docs omit: the `AutoDeviceSelector` race, raw streams pausing in the background, and a photo transfer knocking out other capabilities.
  - Real-glasses issues: a ~53° FOV crop (#140), Bluetooth contention between camera and mic (#159/#206), slow photo transfers (#199/#215), and an MDK session stuck in STARTING (#237).
- **Prior-art search.** Two public repos target this exact brief, both created 2026-11-24:
  - `vaibhavgupta856/FieldCapture`: an Android scaffold with no measured numbers and no backend.
  - `AgrimaBhatnagar/FieldCapture`: a Python web prototype plus an uncompiled iOS scaffold.

  Neither can be "directly implemented" without inheriting unmeasured or uncompiled work, so we build our own. We borrow patterns, not code, from Meta's official samples. The plan targets the gaps both leave: measured thresholds, a real eval, a real back office, tested lifecycle edge cases and a zero-touch walk.
- **Environment check:**
  - Android Studio (Narwhal, JBR 21) is installed, but the **Android SDK is not**; `%LOCALAPPDATA%\Android` is empty.
  - `gh` is authenticated as `suraj2022s`.
  - `uv`, ffmpeg and Node are available; no docker.
- **User decisions:** Android phone · Anthropic Claude (benchmark Haiku 5.5 / Sonnet 5.5 / Opus 5.5) · stretch goals = size without depth + cost & latency · repo private until submission.
- Wrote `docs/PLAN.md`, `docs/RESEARCH.md`, `CLAUDE.md` (session bootstrap) and this journal. Created the private GitHub repo `suraj2022s/FieldCapture` and pushed the first commit.

### Decisions (and why)
- **DAT 1.1.0, not 1.0.x.** It's the same 1.x line, and it adds the session pause/resume API plus typed stream-pause errors.
- **Phone mic + on-device ASR as the primary voice path.** The mock has no glasses audio, DAT audio/speech can't be published, and HFP/SCO fights the camera on real glasses. On-device ASR also works offline (the attic case).
- **Python FastAPI backend.** It keeps the API key off the phone, gives the eval the same extractor code, and provides a real back-office view.
- **QC metrics in a pure-Kotlin module** shared by the app and the threshold-measurement CLI, so the measured thresholds come from the shipped code.
- **Brief kept out of git**; we paraphrase requirements instead.
- **Planned directly in files, not in Claude Code Plan Mode.** Plan Mode is read-only, and this step also had to create the repo and journal. The user asked whether Plan Mode was used. Offered to use Plan Mode for review before each coding milestone.

### Verified
- Repo has no secrets (`private/`, `.env`, `local.properties` gitignored); first push succeeded.

### Next (M1: Android foundation)
1. Install the Android SDK (platform 36, build-tools, platform-tools).
2. With the user's OK, install Meta's `mwdat-android` Claude Code plugin (skills, docs MCP, MockDevice MCP).
3. Scaffold `android/`, pinned to the CameraAccess sample's versions, with DAT 1.1.0.
4. Build `Wearables.initialize`, registration and permissions.
5. Build the MDK debug panel, `WalkSessionManager` and `FrameRingBuffer`.
6. Write instrumented lifecycle tests L1–L8, and measure mock behaviour (first-frame time, frame sizes, `capturePhoto()` output).

### Open questions for the user
- What exact time did the brief arrive? (Sets the deadline.)
- Which phone model and Android version? (On-device speech availability.)

---

## 2026-11-25 — Session 1, continued: M1–M3 code (Android app + back office)

### User answers
- The brief arrived **2026-11-24**; submit by **2026-11-27**.
- Phone: **OPPO A6x 5G (CPH2823)**, ColorOS 16.0.5 / Android 16, Dimensity 6300 (MediaTek), 4 GB RAM.
- The user asked which Claude model is best. Answer given:
  - Opus 5.5 for images (nameplate, damage), about $0.30–0.45 per job.
  - Haiku 5.5 for notes.
  - The eval will compare Haiku/Sonnet/Opus. If Haiku holds up on clean frames, use a Haiku→Opus cascade as the cost stretch goal.
- The user asked whether Plan Mode was used. No: it's read-only and couldn't create the repo. I offered to use it before coding milestones.

### Done
- **Android SDK installed** (platform 36, build-tools 35/36, platform-tools 37.0.3).
  - `sdkmanager` is deprecated and now forwards to a new `android` CLI that collects metrics, so we pass `--no-metrics`. Package names use slashes.
  - The licenses were accepted on the user's behalf for the unattended install.
- **API surface verified with `javap`** against the DAT 1.1.0 AARs from Maven Central. The code is written against real signatures, not memory.
- **Android app** (`android/`) builds. 22 JVM unit tests pass (QC 11, parser 11).
  - `GlassesController`: session supervisor around `DeviceSession`.
    - `SpecificDeviceSelector`; camera attached once at STARTED.
    - `pause()`/`resume()`.
    - STARTING watchdog (issue #237), link-loss detection, unexpected camera stop, reconnect back-off.
    - Typed stream-pause guidance.
    - Serialized `capturePhoto()` with a 15 s timeout.
    - First-frame and fps measurement.
  - `MockGlassesController`: quick setup (pair, power, unfold, don, phone back-camera feed), chaos levers, MockDevice MCP test server.
  - `quality-core` (pure Kotlin): metrics, gate and best-frame selection. The same code is used for measuring thresholds.
  - Voice:
    - `SpeechListener`: phone mic, on-device engine preferred, partials for one-shot commands, muted while TTS speaks.
    - `CommandParser`: intent registry, so the live-session 4th command is one entry; `SpokenNumbers` is ready for it.
    - `Speaker`: TTS plus earcons.
  - Capture:
    - `FrameScorer` keeps the best frame per 400 ms for 5 s (reach-back).
    - `TextDetector`: ML Kit OCR for the nameplate gate, plus a spoken serial read-back.
    - `WalkController`: the capture loop. It gives at most 2 specific prompts, then keeps the least-bad frame and flags it; "capture anyway"; retake/undo/status/notes; spoken disconnect guidance.
  - `JobStore`: fsync + atomic rename; outbox markers; "Got it" only once the capture is durable.
  - `UploadWorker`: WorkManager with a CONNECTED constraint and back-off. Idempotency key = item id. 4xx responses are moved aside, never dropped.
  - `WalkService`: foreground service (connectedDevice|camera|microphone) + wake lock.
- **Back office** (`backend/`, FastAPI + SQLite + `uv`). 7 pytest tests pass.
  - Idempotent ingest; schema-validated job record; auto-refreshing review page.
  - Claude extraction via `messages.parse` with Pydantic schemas, plus the refusal fallback on Opus/Sonnet.
  - Cost/latency logged per call; on-disk response cache (eval replay without a key).
  - Calibrated-confidence features.
  - Reference-object size scaling.
  - 15-code line-item rules.
  - The installed SDK is **anthropic 2.14.0**. Its `messages.parse` signature was checked before use.

### Decisions (and why)
- **File-based store instead of Room.** Fewer dependencies, crash-safety that's easy to explain (temp file, fsync, rename), and uploads are a directory scan.
- **Act on partial ASR results only for one-shot commands** ("capture nameplate", pause, status). This cuts about 0.5–1 s; damage commands and notes wait for the final result because they have slots.
- **The vision model never sees the device OCR text.** That keeps OCR agreement an *independent* confidence signal.
- **Size without depth.** The model only locates the damage box and reference objects; the scaling maths is ours. The model's own estimate is kept as method B for the tape-measure comparison.
- **Debug evidence source is `capturePhoto()` when it passes QC, else the QC'd stream frame.** The stream frame is always kept too, for the source comparison in the eval.

### Not verified yet (needs the phone)
- Runtime behaviour on the mock: first-frame time, frame layout (I420 vs NV12/NV21; `frame_layout` and `debug_frame` log lines), what `capturePhoto()` returns with a phone-camera feed, and whether raw streams pause when the app is backgrounded.
- Whether on-device ASR is available on the OPPO.

### Next
1. User: enable USB debugging and connect the phone. Then `installDebug`, run the end-to-end loop against the local back office (`adb reverse tcp:8000 tcp:8000`), and record the mock measurements in RESEARCH.md §5.
2. M4: dataset mode (save every candidate frame + on-device metrics); labeling page; `eval/qc_thresholds.py` → `assets/thresholds.json`.
3. User needs an Anthropic API key in `backend/.env` before M5 (extraction eval).

---

## 2026-11-25 — Session 1, continued: eval tooling, tests, CI, README (phone still not connected)

### Done
- **Dataset mode (app).** Every frame the quality gate judged during a capture is saved, not only the one kept: JPEG plus the phone's own metrics, verdict, OCR and the chosen flag. Output goes to `Android/data/app.fieldcapture/files/dataset/`. Turn it on in Settings (debug).
- **Eval tooling (`eval/`):**
  - `pull_dataset.ps1`
  - `label_app.py`: local labelling page for frame labels, scene and condition per capture, and ground truth per scene.
  - `qc_thresholds.py`: fits each threshold at ≤5% false retakes on tune scenes, tests on held-out scenes, writes app `assets/thresholds.json`.
  - `run_extraction.py`:
    - per-model strict scoring, where abstaining counts as correct if the field isn't printed, plus CER
    - damage size error per method, including scaling with the tape-measured reference
    - notes vs `labels/notes_truth.json`, with ASR WER
    - responses cached in `eval/llm-cache` for key-free replay
  - `calibrate.py`: logistic confidence fitted on tune scenes; AUROC/ECE reported on test scenes.
  - `report.py`: generates `REPORT.md`, including an offline Haiku→Opus cascade simulation.
  - `README.md` with the shot list; `notes_script.md` (20 lines).
- **Instrumented tests** on Mock Device Kit. They compile, but haven't run yet because there's no device.
  - `LifecycleTest`: L1 start/stream/photo/stop · L4 pause/resume · L5 doff/don · L6 fold/unfold · L7 power loss mid-walk → reconnect · L8 overheating · L9 outdated glasses app.
  - `VoiceToCaptureTest`: inject "capture damage, kitchen, north wall" and a note into the real app graph; assert both are durable and queued.
  - Synthetic H.265 test feed (`test_feed.mp4`: ffmpeg test pattern plus a fake label). It's test-only, never eval data, and is also in debug assets for the MockDevice MCP.
- **CI** (`.github/workflows/ci.yml`): JVM tests, debug APK artifact, androidTest compile, backend pytest. `gradlew` was marked executable in git.
- **README** rewritten: one mermaid diagram, setup under 10 min (CI APK + `uv run`), capture flow, decisions and cuts, models/cost (pointing to the report), limitations, two-more-weeks list, AI tools used, repo map.

### Decisions
- **The test set is built from frames the phone's gate actually saw** (dataset mode), labelled by a human. The thresholds then come from on-device numbers, not a re-implementation.
- **Scene-level tune/test split** (hash of the scene id) to avoid leakage between frames of the same object.
- **Cost stretch goal: simulate the cascade offline** from per-model results. No extra API spend, and the comparison is like-for-like.

### Next (in order)
1. Phone connected → `installDebug` → run `connectedDebugAndroidTest`. Record the mock's measured behaviour (timelines from the tests, first-frame ms, frame layout, `capturePhoto()` output) in RESEARCH.md §5.

---

## 2026-11-25 — Session 1, evening: phone connected, all on-device tests pass, first real voice walk

### Phone setup gotchas
- The phone was connected in **USB tethering** mode, and the PC's only internet ran through it, so switching USB mode would have cut this session. USB debugging works alongside tethering. The PC later moved to Wi-Fi.
- `adb reverse tcp:8000 tcp:8000` is lost on every USB disconnect; re-run it.
- **`connectedDebugAndroidTest` uninstalls the app afterwards.** Reinstall with `adb install -r -g` before manual use.

### Measured (details in RESEARCH.md §5a)
- Mock registration works without the Meta AI app.
- First frame 1.8–2.6 s after `createSession`.
- The phone-camera feed is **480×640 @ ~30 fps whatever we request**.
- Raw frames are **tight I420**.
- `capturePhoto()` gives a 480×640 HEIC with the phone feed, but a **1×1 placeholder** with a video feed.
- Mock `doff()` does not pause the stream.
- `setCameraFeed(Uri)` does not switch a running stream.

### Tests
- **8/8 instrumented tests pass on the OPPO.** Two real bugs were found by them and fixed:
  1. Stopping a foreground service before `startForeground()` crashes the app on Android 12+. The service is now only started when a matching permission is granted.
  2. Doff wasn't suspending capture on the mock. Capture is now suspended from don/hinge state.
- Parser tests 14/14 and backend 7/7. CI green on GitHub.

### First natural voice walk
- The user ignored the script and spoke naturally, which was the best test. 6 damage captures, all uploaded, and the back-office record is schema-valid.
- The dark-floor capture got "Too dark…" twice, then auto-accepted a good frame: the demo's caught failure.
- Speech→command 2.6–5.1 s; command→saved 0.6–1.2 s.
- The house has **real seepage damage** (peeling, flaking paint at a wall/ceiling junction), so no staging is needed.
- Fixes it drove:
  - forgiving parser (fillers, loose "capture X", spoken hint when not understood)
  - recognizer confidence 0.00 treated as unknown
  - ERROR_CLIENT no longer triggers the engine fallback
  - a one-time room tip

### Privacy notes for filming
- A debug frame caught the laptop screen showing the user's email, and an evidence photo caught a framed portrait. Neither is in git: `backend/data` and the scratchpad are ignored.
- For the dataset and demo: no screens, documents or people (including framed photos).

### Next
1. User: Anthropic API key → `backend/.env`. Then restart the back office with extraction on, `POST /v1/jobs/{id}/reprocess` for today's job, and check damage type + line items on the real seepage photos.
2. Tomorrow (11-26): film the dataset with **Dataset: ON** (shot list in `eval/README.md`), label, then run the eval.
3. Optional OK from the user: install Meta's `mwdat-android` Claude Code plugin.
4. Perf note: frequent GCs (~20 MB large objects per cycle) from per-frame copies in `FrameScorer`. Pool buffers if frame drops show up.

---

## 2026-11-25 — Session 1, late evening: kits installed; hand-over to a new session

### Done
- With the user's OK, installed **Meta's `mwdat-android` Claude Code plugin 1.1.0** at project scope (`.claude/settings.json`, committed): 18 skills, the `wearables` docs MCP and the `mockdevice` MCP. `python3` resolves to Microsoft Store Python 3.13, which is fine for the MCP.
- The user asked to "search for any other kit and use it". Findings:
  - **Google's official Android skills** were the useful find. Installed five locally in `.claude/skills/` (gitignored, reinstall steps in CLAUDE.md).
  - Skipped `android-profiler` because it bundles a native binary.
  - Skipped community plugins: unofficial.
  - Meta's other kits are iOS / display-only.

### For the NEW session (the user is opening a fresh conversation so the plugin and skills load)
1. **Re-arm the phone link.** Check `adb devices`, then run `adb reverse tcp:8000 tcp:8000`.
2. **Start the back office.** From `backend/`, run `uv run fieldcapture-server`. Set `$env:FIELDCAPTURE_EXTRACT='0'` until `backend/.env` holds `ANTHROPIC_API_KEY`.
3. **Check the MockDevice MCP.** Launch the app with `adb shell am start -n app.fieldcapture/.MainActivity --ez mwdat_ui_testing true`, then call `mock_connect(bundle_id="app.fieldcapture", platform="android")`.
4. **When the user adds the API key**, restart the server with extraction on, then `POST /v1/jobs/5482e66c-f4fe-4ff7-88fc-c1d5c3616ba3/reprocess`. That is today's 6 real captures, including the seepage wall. Inspect the fields and line items, and fix the prompts if needed.
5. **11-26 filming:** dataset mode ON, shot list in `eval/README.md`, then labelling → `qc_thresholds.py --write-app` → `run_extraction.py` → `calibrate.py --write` → `report.py`.
6. **11-27:** 3 walk-throughs (sample records), demo video from the `demo-v1` tag, `docs/MEMO.md`, final README numbers, then the repo goes public (ask the user first).
2. Pick the stream YUV layout from the debug dumps, then fix the `AppSettings.yuvLayout` default.
3. User films the dataset per `eval/README.md` (daylight + evening low-light), then labels it.
4. User adds an Anthropic API key to `backend/.env`. Then run `run_extraction.py`, `calibrate.py --write`, `report.py`.
5. Three real walk-throughs (sample records), demo video (tag `demo-v1`), memo, final README numbers.

---

## 2026-11-25 — Session 2 (new conversation, plugin loaded): resume after app kill, adb walk driver

### Re-armed (per the hand-over)
- Phone on USB; `adb reverse tcp:8000 tcp:8000` re-run. It was lost again on reinstall, so re-run it after every `installDebug`.
- The back office from session 1 was still running on :8000 with extraction off. `backend/.env` doesn't exist yet, so there's no API key and no reprocess.
- **The MockDevice MCP can't connect here:** it shells out to plain `adb`, and platform-tools isn't on PATH. Workaround: `adb forward tcp:8237 tcp:8237` and call the test server's HTTP routes directly (health OK, 55 routes). The real fix (PATH + VS Code restart) needs the user's OK.
- The stale "fix the `yuvLayout` default" item was already done (I420).

### Found and fixed: a walk killed by the OS was never resumed or finalized
- **Symptom.** Both of the user's real jobs from this afternoon were still open on the phone (`endedAt: null`). Any process death (ColorOS kill, crash, reinstall) left the job open forever, with no `finalize`. The tech's next "start job" would have opened a *new* job id and split the record.
- **Fix.**
  - `JobStore.recoverOpenJobs`: the most recently active open job is resumed on launch, under the same id. Counts, current room and the saved timeline are restored; "Resumed your job. 6 captures, 0 notes so far." is spoken.
  - Any other open job is closed and finalized, with a timeline event saying why. Nothing is deleted.
  - `WalkController.timeline()` now keeps the events saved before the restart, which used to be lost.
- **Decision: a 30-minute *inactivity* window, not "since job start".** My first draft used 12 h since start. That would file captures under the wrong address if the tech reopens the app hours later at another house. A killed app is noticed within minutes. Activity is the newest of job start, any capture or note, and any saved event, so a long walk with recent captures still resumes.
- **Known limit.** "Undo" only reaches captures made after the restart, because the in-memory history is gone.
- **Verified.**
  - 5 new JVM tests (`JobStoreRecoveryTest`), 19/19 app JVM tests pass; androidTest still compiles.
  - On device with real data: job `5482e66c` was resumed with its 6 captures; `c9d5eded` was closed and finalized. Both finalizes reached the back office and the outbox drained to 0.
- **Side finding.** The route was restored at 18:41:36, but the upload only went at 18:41:56. WorkManager's exponential backoff (10 s, 20 s, 40 s…) had grown, and `kick()` with `APPEND_OR_REPLACE` queues *behind* the waiting retry. After a long dead zone, the wait could be minutes. The chaos run will measure it before anything is changed.

### Added: debug-only adb driver (`src/debug/.../DebugCommandReceiver`)
- `am broadcast … --es cmd start|end|say|offline|online|status|feed_test|feed_camera`.
- `say` enters through `WalkController.simulateHeard`, the same path the mic and VoiceToCaptureTest use.
- It's protected by `android.permission.DUMP`: adb's shell holds it, ordinary apps can't get it. It's in the debug source set only, so it's absent from release builds.
- `feed_test` (the synthetic clip) is persisted, so a scripted run that kills the app comes back to the same feed and never films the room.
- Purpose: repeatable chaos runs and the live-session failure drill without anyone speaking.

### Mistake to learn from
- I ran a short scripted check while **the user was talking to the app**: "Please capture the wearing of paint" was heard and saved.
- My script had started job `4f6a8eef` on the synthetic test feed, so the user's live captures went into that job and show a test pattern.
- I reverted the feed setting to the phone camera at once and stopped driving the phone.
- Rule added to CLAUDE.md: check logcat for recent `[heard]` lines and ask before any scripted phone run.

### Also found
- **The PC's internet runs through the phone's Wi-Fi hotspot** (`ap0`). So the chaos run must never use real airplane mode or toggle data/Wi-Fi; it uses `adb reverse --remove`, the app's offline switch, a stopped server and process kills. The "no network at all" path (WorkManager's CONNECTED constraint) therefore stays untested on device, and that will be flagged.
- **Docs corrected.** CLAUDE.md and PLAN.md said "DAT Speech sits behind a flag", but it was never built; `mwdat-speech` isn't a dependency. The README was already accurate.
- **Mock video feed.** Our 8 s, 540×960 clip arrives at the requested 15 fps and keeps flowing past 8 s (RESEARCH §5a).

### Next
1. **User:** is job `4f6a8eef` yours to keep? Its captures are of the synthetic test pattern. Say "end job" and start a fresh one for real captures. Also tell me when the phone is free for an unattended run of about 5 minutes.
2. Chaos run (`tools/chaos_run.ps1`, separate server on :8001 with its own data dir, synthetic feed): online baseline → back office unreachable → app killed with items queued → resume → route restored (measure time to drain) → glasses power-off mid-walk (refusal, note still saved, reconnect time) → end. Report captured vs received vs replays.
3. Still waiting on the user: API key → reprocess `5482e66c`; the user's OK to put platform-tools on PATH for the MockDevice MCP; 11-26 filming.

### Addendum: chaos-run script ready, not yet run
- `tools/chaos_run.ps1` (+ `tools/chaos_counts.py`) does the six-phase run described above. Every duration is measured on the phone's clock (the receiver logs each command), so PC/phone clock skew doesn't enter.
- Safety rails:
  - **Preflight:** it refuses to start if the app heard speech in the last 2 min, a job is open, or real uploads are still queued.
  - **Isolation:** its own back office on :8001 with a temp data dir; the synthetic feed; the mic off; no airplane mode.
  - **Cleanup:** it ends and drains its own job, puts the feed back on the camera and restores `adb reverse` to :8000.
- **Verified so far:**
  - The script parses.
  - `chaos_counts.py` reads the real finalized job `5482e66c` correctly (6 captures, finalized, nothing uploaded twice).
  - The app builds, and the JVM tests pass after the `mic_off`/`mark` commands and note-id logging were added.
- **Not run yet:** the user is using the phone. The new build is **not installed**, because installing would kill the user's running walk.

---

## 2026-11-25 — Session 2, continued: Meta's DAT skills used for real; API key; README tool table

### The user asked: "are we using Meta SDK skills?"
- Honest answer at the time: **only partly.**
  - The plugin was installed, but this session had loaded none of its skills and hadn't used the docs MCP.
  - The MockDevice MCP had been tried, but it fails here because it needs `adb` on PATH.
  - In session 1 the skills were read from the SDK repo, not loaded.
- The user asked for the AI tools to be used, and for the README to say which tools were used and where.

### Done
- **Loaded `dat-conventions`, `session-lifecycle`, `camera-streaming` and `mockdevice-testing`** and audited `GlassesController` against their checklists. Five gaps, all fixed:
  1. **Collectors subscribe before `start()`.** They now use `CoroutineStart.UNDISPATCHED`; a plain `launch` only schedules the collector, so an early `errors` emission could be missed. The session `state` collector previously ran *after* `start()`. The same fix applies to the stream's flows before `stream.start()`.
  2. **A failed `addCamera` or `stream.start()` ends the session** so the supervisor retries. Before, the walk sat in CONNECTING forever with no camera and no guidance.
  3. **Camera permission is checked before every session**, with a new `NEEDS_PERMISSION` phase and spoken guidance, re-checked every 5 s. A walk resumed after an app kill, or a reconnect, never went through the Activity's permission flow.
  4. **pause/resume settling.** A "resume job" that arrives before the device confirms PAUSED is ignored by the SDK, so it's now re-issued when PAUSED is observed.
  5. **A stream that stops under a started camera is restarted**, as the docs say a started camera's stream can be. It's capped at 3 restarts per session, then the session is rebuilt. It never restarts while the session is pausing or stopping, or after a user pause.
- **Docs MCP (`search_dat_docs`)**: cross-checked our measured mock behaviours against docs v1.1 (RESEARCH §5b).
  - Undocumented, so they stay as "[ours]" memo claims: the I420 layout, doff not pausing the mock stream, the in-stream 1×1 placeholder photo, and the phone feed ignoring the requested quality and fps.
  - Implied by the docs: mid-stream `setCameraFeed` is ignored.
  - New documented fact for the memo: the mock "does not model Stream and Photo contention" and its transfer timing "is not representative".
- **Google's `android-intent-security` skill**: audited the exported components. No code changes. One debug-only risk is documented in the README: the `mwdat_ui_testing` extra on the exported launcher starts the loopback test server. DUMP was kept for the receiver, because a custom signature permission would block adb's shell, its only intended sender.
- **README "AI tools used"** is now a table of tool → what it was used for → where it shows. It corrects the earlier unverified claim that Google's skills were "used for device automation, test setup…": only `android-intent-security` has been used. The README's limitations also gain the resume window, the offline-test scope and the debug-only surfaces.

### API key and funding
- The user created `backend/.env` from the template and added a key. It's valid: Anthropic authenticated it.
- The account has **no credit**. All 6 reprocess calls returned HTTP 400 "credit balance is too low". Nothing was billed, and errors are raised before the response cache is written, so a re-run will call the model.
- **The user asked how much to add.** Recommended **$25**: about $0.30 for today's re-run, about $9–10 per full three-model eval pass over ~120 frames, a second pass for prompt tuning, and about $3 for walk-throughs. These are estimates from `config.py` prices and my token assumptions; the eval measures the real cost. That stays under the brief's $50 cap. Auto-reload off; receipt into `private/`.
- **Back office:** I wasn't allowed to stop the old :8000 server (extraction off). So a second server runs on **:8002 with extraction on**, sharing the same `backend/data`, and the phone is routed to it (`adb reverse tcp:8000 tcp:8002`). The old server sits idle.

### Not verified yet
- **The 8 instrumented MDK tests have not been re-run after the `GlassesController` changes**, because the user is using the phone. The build compiles, androidTest compiles and the JVM tests pass. Run `connectedDebugAndroidTest` before tagging `demo-v1`. It uninstalls the app afterwards, so reinstall with `adb install -r -g`.

### Next
1. User adds credit → `POST :8002/v1/jobs/5482e66c…/reprocess` → check the damage types and line items on the real seepage photos.
2. Phone free → install the new build → instrumented tests → `tools/chaos_run.ps1`.
3. 11-26 filming per `eval/README.md`.

---

## 2026-11-25 — Session 2, evening: phone free; instrumented tests re-run; chaos run PASS

### Phone link
- The user turned developer options off, then back on, and restarted the phone.
- **USB debugging doesn't come back on by itself:** Windows saw the phone only as a file-transfer device (WPD) until USB debugging was switched on again.
- Afterwards the phone was re-authorized, the route set to :8002, and the app was still installed with 0 uploads waiting.

### Instrumented tests after the Meta-skills audit
- **8/8 Mock Device Kit tests pass on the phone** (24 s).
- The phone's upload route was removed first, so the tests' synthetic captures couldn't upload into the real back-office data. The earlier sessions' job list on :8000 likely holds a few test jobs for exactly that reason; worth checking before samples are picked.
- Reinstalled with `adb install -r -g`: all 4 runtime permissions were granted.

### Chaos run (`tools/chaos_run.ps1 -OutageSeconds 60 -RestorePort 8002`)
- **Run 1 (`eval/chaos/chaos-20261125-195142.json`, verdict ERROR) found a real bug.**
  - After a reconnect the app never said "Glasses back." A reconnect goes RECONNECTING → **CONNECTING** → ACTIVE, and the line was only spoken when the phase before ACTIVE was RECONNECTING.
  - The lifecycle test L7 checks the phase, not the spoken line, so it missed this.
  - Data was still intact in that run (4 captures + 3 notes on both sides, finalized). Only the comparison step was skipped by the error.
  - **Fix:** `WalkController` keeps an `interrupted` flag: set on RECONNECTING, SUSPENDED or NEEDS_*, and cleared with "Glasses back." on the next ACTIVE. The same flag says "Glasses disconnected" once per outage instead of on every reconnect cycle.
- **Script fixes from run 1:**
  - PowerShell 5.1 turned adb stderr (`find` on a fresh install's missing folder) into a fatal error.
  - Marks were logged after the HTTP call instead of before.
  - Reconnect is now measured to phase ACTIVE, and the spoken line is checked separately.
  - `-RestorePort` added, because the everyday back office is on :8002 now.
- **Run 2 (`eval/chaos/chaos-20261125-195606.json`): PASS.**
  - The phone acknowledged **5 captures and 3 notes, and the server stored exactly those 5 + 3**: 0 missing, 0 extra, 0 captures/notes uploaded twice, finalized.
  - Baseline: save → stored in 103–160 ms.
  - Back office unreachable for 60.7 s: 3 failed attempts, 4 items queued.
  - App killed with the 4 queued: resumed under **the same job id**, counts restored, nothing dropped from the outbox (kill → resumed 5.4 s, which includes the script's 3 s wait).
  - Route restored → all 4 stored in 9.9–10.1 s.
  - Glasses off: "Glasses disconnected" after 5.2 s (our 5 s link-loss grace); capture refused out loud; note saved.
  - Power on → ACTIVE in 262 ms, "Glasses back." at 264 ms.
  - **Caveat:** the JSON's `app_commit` is `de9895e`, but the installed build also carried the uncommitted "Glasses back" fix, which is committed together with this entry.
- **Also measured:** `checkPermissionStatus(CAMERA)` *fails* (doesn't return Denied) while the glasses are off. The new permission gate treats a failed check as "unknown", so it doesn't nag (RESEARCH §5a).
- **Not covered:**
  - The phone fully offline: airplane mode is off-limits here, because the PC's internet runs through the phone.
  - Outages longer than 60 s. WorkManager's exponential backoff means the wait after the route returns grows with the outage; measure with `-OutageSeconds 300` before claiming anything about long dead zones.

### Next
1. User adds API credit → reprocess `5482e66c` on :8002.
2. Optional: a 5-minute outage run to measure the backoff tail.
3. 11-26 filming per `eval/README.md`.

---

## 2026-11-26 — Session 2, past midnight: first real extraction; line-item rules fixed for plaster and concrete

### Extraction on the first real job (`5482e66c`, 6 damage captures)
- The user added API credit. A reprocess on :8002 ran 6 Opus 5.5 calls, all 200 OK.
  - Cost **$0.1346** (15,921 input / 3,546 output tokens, about $0.022 per photo); model latency p50 **8.96 s**.
  - Record schema-valid.
  - The record's `capture_to_extracted_ms` (~1.7 h) is meaningless for a *reprocessed* job. Latency for the report must come from live walks.
- **My own look at the 6 photos.** This is an anecdotal check (n=6), not the eval; reported numbers come from the labelled test set.
  - #3 "seepage on wall": **correct**. Peeling paint with the older blue coat exposed along the wall–ceiling junction; `peeling_bubbling_paint` 0.85, plaster, moderate.
  - #1 "broken wall" and #2 "broken switchboard": the model **abstained** (`none`, 0.30) on wide shots where no damage is visible at 480×640. That's a framing problem at capture time, not a model error, and both are flagged for review.
  - #4: crack plus exposed wiring (0.50). #5: minor flaking (0.60). #6: possible water staining on a concrete/terrazzo floor (0.40, hedged).
  - Every capture has `room_missing` (no room was spoken), and all sizes are low-confidence. There was no reference object in frame, so the size is the model's guess.
- **Privacy:** #3's evidence frame shows a framed photo of a person and a religious picture. Keep it out of `samples/` and the demo, or crop it.

### Line-item rules were wrong for this house, now fixed
- **Before:** any water-type damage on any wall → `DRY-RR`; any floor damage → `FLC-RR`; model-guessed sizes → firm quantities. On plaster walls and a concrete floor that drafted **40 SF of drywall replacement and 10 SF of floor covering**, neither of which exists there. The 66 SF repaint was presented as if measured.
- **Now:**
  - Surface-only damage (stain, peeling paint, efflorescence) on a paintable surface → `PNT-SPR` only. The substrate is assumed sound and dry, and the basis text says to verify that with a moisture reading.
  - Substrate damage on drywall or unknown board → `DRY-RR`/`CLG-RR` as before. A non-drywall material is flagged "confirm it is board".
  - Plaster, concrete or brick → `PNT-SPR`. Cracks are filled in prep; anything else gets `needs_measurement` for the estimator to scope.
  - Tile or wood walls → nothing; left to the estimator.
  - `FLC-RR` only on a real covering; an unknown covering is flagged.
  - Sizes below the 0.6 review bar give a ballpark quantity marked `needs_measurement`, with the confidence in the basis.
  - Merged lines no longer repeat the same reason.
  - Code table: `PNT-SPR` now includes scraping and filling minor cracks; `FLC-RR` names wood and carpet.
- **The real job now drafts:** `MST-MAP` 1 EA + `PNT-SPR` 66 SF (**measure**). That's the right scope for seepage on plaster: find the moisture source, scrape, prime and repaint, and tape-measure first.
- **Decision and why:** this is industry practice. A dry, intact stain is sealed and repainted; board is cut out only when it's wet, swollen, rotten or holed. Rules stay deterministic and readable by an estimator, and nothing moves into the prompt.
- **Tests:** backend **12/12** (7 → 12). Two old tests encoded the over-scoping and were rewritten. New cases cover plaster seepage with a guessed size, a hole in plaster, a concrete floor stain, swollen drywall and a tile wall.

### Back office
- The :8002 server (this session's own background task) was restarted twice to load the new rules; the phone is routed to it.
- `adb reverse` was lost again overnight and has been re-armed. It drops on every USB reconnect.

### Next (11-26, filming day)
1. Film the dataset per `eval/README.md`, with **Dataset: ON**.
2. **Name the room** in each command, and **keep a reference object** in frame (a switch plate, an A4 sheet or an ID card) and tape-measure it. Both were missing on day 1, and they're what turn the size guesses into measured scaling.
3. Frame the damage itself, not the whole room (#1 and #2 above).

### External smoke test on openly licensed web images (user request) — NOT eval data
- **Why:** the user asked to find sample images on the web and test them. The brief's hard rule is "every frame in the test set and demo comes from our own captures", so these images are **not** in the test set, `eval/REPORT.md`, the demo or the repo: they're kept local only, which also respects their licenses. The useful part: the nameplate extractor had never seen a real plate, because day 1 had none.
- **Sources:** Wikimedia Commons, via its API (licenses checked per file), 8 images.
  - Plates: Malmbergs motor plate (CC0), Prelude A550 CD-player data plate (CC BY-SA 4.0), Boles-Aero trailer plate (CC0), Rolls-Royce Buzzard gear-unit plate (CC0).
  - Damage: "Ceiling water damage" (CC BY 3.0), "Drywall mould and water damage florida hotel 01" (CC BY 3.0), "Mold on Ceiling on House on Dublin Street New Orleans after Katrina" (CC BY 3.0), "Water Damage" (CC BY-SA 3.0).
  - Three more (a PBY-5 plate and an aircraft tag, both with printed dates, and a peeling sheetrock ceiling) were refused with HTTP 429. I stopped retrying, as Wikimedia's message asks.
- **Run:** the production code paths with production settings (Opus 5.5, medium effort): `nameplate.extract`/`to_fields` with no device OCR, and `damage.extract`/`to_fields` → `lineitems.derive` with no voice slots. **$0.167 for 8 calls** ($0.017–0.027 each), 5–9 s per call.
- **Plates:** every make, model and serial matched my own reading of the plate. The CD player's "serial" is an unlabelled sticker, so its ground truth is itself uncertain.
  - No plate prints a date, and the model returned null for all 4: **no invented dates**. It also returned no model number for the trailer, which has none.
  - Confidence dropped on the small, glary Rolls-Royce plate (0.65–0.68).
- **Calibration finding:** the unlabelled CD-player serial had a self-confidence of 0.60, but the **pre-data confidence prior raised it to 0.786**, above the 0.6 review bar. The constant bonuses (format looks plausible + "good" legibility + QC pass) outweigh a cautious self-score when there's no OCR agreement. `eval/calibrate.py` must be fitted on real captures before the demo; until then the default priors over-trust ambiguous fields.
- **Damage:** readings are plausible; three of the four match what I see. On the "drywall mould" photo the model described cracked, lifting paint with tan water spots, which matches the image better than its file name (no mould visible in that frame).
  - Line items under the new rules: board ceiling stains → `PNT-SPR` + `INS-RR` "consider" + `MST-MAP`; the Katrina mould ceiling → `MLD-REM` 120 SF (containment) + `ANT-APP` + `PNT-SPR`.
  - Every size was a 0.2–0.4 guess, so every quantity is marked `needs_measurement`, as intended.
- **Caveat:** these are sharp, well-lit, high-resolution web photos, much easier than 480×640 glasses frames. They show the extractors and rules work end to end and fail safe. They say nothing about accuracy on our captures, which is what the eval measures.

---

## 2026-11-26 — Session 2, midday: full pipeline rehearsal before filming (web images as a stand-in, not data)

### Why
The user asked for the pipeline to be tested with web images before filming, so that real captures need "minimal updating" afterwards. Dataset mode, the pull, labelling, threshold fitting, extraction, calibration and the report had never run on any data. The rehearsal ran in a **copy of `eval/` in the scratchpad**: no rehearsal frame, label, result or report is in the repo. The phone's rehearsal data was deleted afterwards.

### How
- The 8 Commons images, each as good / dark (×0.13) / blurred (σ=7), were made into a 240 s, 540×960 H.265 clip, pushed to the phone and played as the **mock glasses feed**. That needed a new debug command `feed_file`, plus `dataset_on` / `dataset_off`.
- 24 scripted captures with dataset mode ON, the mic off and a throwaway back office on :8001.
- Pulled with the real `pull_dataset.ps1` (293 files), auto-labelled (standing in for the labelling page), then freeze → `qc_thresholds --write-app` → `run_extraction` (3 models) → `calibrate` → `report`.

### What worked first time
- Dataset mode on the device: 24/24 captures saved every candidate frame, with on-device metrics and OCR.
- The pull script.
- Labelling page: 24 captures, 245 frames, 5 scenes rendered; the label API saves; path traversal is blocked.
- Extraction: 132 calls, 0 errors.
- Notes scoring from the back-office DB.
- The app loads a `--write-app` thresholds file (`thresholds=measured-n133` at start-up; the file was removed again afterwards).

### What the rehearsal found and fixed
1. **Cost.** Extraction scored every labelled frame. With ~10 frames saved per capture, the real set would have cost ~$20–25 (all of the API credit), mostly on near-duplicates. Now it scores 2 frames per capture (the app's chosen frame plus the worst-scoring one, `common.sample_frames`), configurable with `--per-capture`. Measured per frame: Haiku $0.0005, Sonnet $0.008, Opus $0.016, so the real eval is ~$3–5.
2. **The committed test set had no producer.** The scripts read `eval/raw` (gitignored), the report linked to it, and `--cached` replay needs the frames. New `eval/freeze.py` copies labelled captures (`capture.json` plus the scored frames) into `eval/dataset/`. Scripts read that once it exists; the labelling page always uses the raw pull. A blank scene keeps a capture out of the public repo (privacy). The rehearsal's 24 captures froze to 3.7 MB. **`--cached` replay from the frozen set reproduced all 132 rows in 5 s for $0.**
3. **Threshold fitting overfit to the edge of the usable frames.** It set e.g. `minMeanLuma 121`, `minSharpness 500` and `maxDarkFraction 0.03`, so a normal indoor frame would have been prompted "too dark". Within the false-retake cap it now takes the middle of the gap between the classes (geometric for sharpness): 75.8 / 34.9 / 0.30, with identical tune and test performance.
4. **Calibration would refuse to run** on realistic data: Opus and Sonnet were **never confidently wrong**. On dark and blurred plates they returned null instead of guessing (13 times), which left nothing to calibrate against. It now measures leave-one-scene-out (all data, no leakage by scene), needs ≥ 3 wrong answers, and otherwise reports the abstention counts and keeps the prior instead of exiting with an error. It writes one result per model, so Haiku, whose confidence drives the cascade, can be calibrated separately.
5. **Report fixes:**
   - Extraction accuracy is now on all scenes (nothing is fitted on those labels), roughly doubling n; the quality gate stays on held-out scenes.
   - New *read rate / invented values* table. **0/49 invented values for every model.**
   - The five worst failures: one row per capture, misses (printed but left blank) count, drawn from all scenes, never an empty table.
   - **The cascade simulation escalated 100% at every τ** because a blank, unprinted date has confidence 0. It now escalates only on a low-confidence *answer* or a blank make/model/serial: Haiku-first at τ=0.5 escalated 68% of plates with the same accuracy, at 0.69× the cost.
   - Cosmetic: `nan` → "—", and the edge sign on upper rules.
6. `run_extraction --workers 4`: the real set should take ~5 min instead of ~17.

### Mock Device Kit finding
A video feed plays **one source frame per stream frame at the stream's rate**: a 24 fps clip streamed at 15 fps runs at 0.625× real time. This was found because the clip ran out of step with the timed captures. It's confirmed from frame luma at three points and explains the earlier "8 s clip flows past 8 s". RESEARCH §5a; CLAUDE.md says to encode mock clips at 15 fps.

### Verified
- Backend 12/12.
- The rehearsal ran end to end twice (live API, then `--cached`).
- The app was rebuilt without the rehearsal thresholds: `seed-2026-11-25` at start-up.
- The phone's dataset folder is empty, dataset mode is off, and the route is back to :8002.

### For the real data (what's left is only the human part)
pull → label (scene, condition, frame labels, ground truth) → `freeze` → review `eval/dataset` for privacy → the 5 commands in `eval/README.md` §3 → write `failure_causes.json` → re-run `report.py` → commit `eval/dataset`, `eval/labels`, `eval/llm-cache`, `eval/results` and `REPORT.md`.

## 2026-11-26 14:39 IST: phone smoke test before filming: PASS

### Why
The user asked for one test of the phone before filming the dataset.

### How
New `tools/phone_smoke.ps1` (about 1 min). Its preflight matches the chaos run: no recent speech, no open job, nothing queued, and the dataset folder empty.
- **A.** One job on the synthetic test feed with dataset mode ON: `capture nameplate`, `capture damage, kitchen, north wall`, a note, end job, then the record from the :8002 back office (extraction on).
- **B.** The phone's back camera as the glasses feed: start a job, stream for 8 s, end it. No captures, so nothing from the home is recorded.

### Result (installed build 12:02, the latest; seed thresholds)
- **Phone:** all runtime permissions granted (camera, mic, notifications, Bluetooth, foreground service types). Standby bucket 10 (active). 52 GB free. On-device ASR engine, en-US.
- **A:** streaming 1.3 s after start, 15.0 fps.
  - **Nameplate:** the gate prompted "Label is cut off. Step back a little" twice (the test pattern's text runs to the frame edge), then kept the least-bad frame at the 7.3 s timeout. Dataset mode saved 26 frames. Upload 0.3 s.
  - **Damage:** passed first time, 4 frames, saved in 38 ms.
  - **Extraction:** ACME HEATERS / XE50M06 / Q011504893 at confidence 0.98. Date, capacity, power and voltage are blank, not invented: they aren't printed. On the damage frame the model called it a test pattern with no damage (`none`, 0.45), so **no line items were invented**.
  - **Totals:** 3 model calls, $0.033, capture → extracted p50 5.7 s, schema valid.
  - **Cleanup:** the smoke job's dataset frames were deleted.
- **B:** streaming 2.2 s after start at 30 fps (the phone-camera feed ignores the requested 15 fps; RESEARCH §5a). The job ended and the outbox drained.
- **Left on the phone:** feed = phone back camera, dataset mode OFF, no open job, empty dataset folder, `adb reverse` 8000 → 8002.

### Next
The user films the dataset (Dataset ON, then the shot list in `eval/README.md` §1), then pull → label.

## 2026-11-26 14:53 IST: bug: no "Start job" button after a job ended

### Found
The user asked whether the UI is good, so I looked at a screenshot instead of the code. After the smoke test ended its job, the screen still showed the job (Pause / Resume / End job) and **no Start job button**.

### Cause
`endJob()` cleared the controller's job but left `jobId` in the UI state. The screen picks "Start job" vs "job in progress" from that field, so a second job couldn't be started without killing the app. The same field drives `FLAG_KEEP_SCREEN_ON`, so the screen also stayed awake after the job ended. Every scripted run (chaos, smoke, instrumented tests) starts jobs through the debug driver or the controller, never the button, so none of them could catch this.

### Fix
`endJob()` resets the UI state, keeping a "Last job: room, N captures, M notes" line, and the no-job view shows that line plus any uploads still waiting.

### Verified on the phone
Debug start → job view → debug end → **Start job** is back with "Last job: 0 captures, 0 notes"; screenshots before and after. App JVM tests pass.

## 2026-11-26 15:25 IST: full bug scan before filming: 4 bugs fixed

### Why
The user asked for a full scan of every screen (scrolled, not just the top), thorough testing, open-source testing tools from GitHub, and fixes before they film.

### How
- **Static.** Android lint (`:app:lintDebug`): 0 errors and 24 warnings. Most are newer library versions, which we pin to CameraAccess on purpose. `allowBackup` is already false, so job photos never go to cloud backup. The phone-only portrait lock is fine on this phone.
- **Every screen.** A scratchpad UI driver reads the Compose tree with `uiautomator dump`, scrolls to each label, taps its centre and checks the crash buffer after every tap. Swipes stay mid-screen so they never pull down quick settings, which hold this PC's hotspot. Screenshots were taken top to bottom, idle and during a job.
- **Every control and command.** All four settings toggles and the YUV cycle. The measure buttons with and without a stream. All 14 mock buttons, idle and mid-job. A whole job driven through the real buttons, covering:
  - Pause ×2 and Resume ×2
  - every voice intent (nameplate, damage with slots, note, undo, retake, status, "start a job" while running, gibberish, a bare "note", the loose "please capture the broken wall", two captures 150 ms apart, pause/capture/resume by voice)
  - glasses power-off, doff, fold, 5% battery and overheat mid-walk
  - the no-signal queue and drain
  - End job while paused, then a second job ended by voice.

  The test feed was used throughout, so nothing from the home was recorded.
- **Web search for open-source test tools:** [Maestro](https://github.com/mobile-dev-inc/maestro) (Apache-2.0, UI flows; needs a global CLI, so not installed without asking), [Schemathesis](https://github.com/schemathesis/schemathesis) (MIT, API fuzzing; **used**), [Toxiproxy](https://github.com/Shopify/toxiproxy) (MIT, network faults; binary download, not installed), and DroidBot / Fastbot / monkey (random GUI exploration; skipped, because random taps can reach quick settings).

### Found and fixed
1. **A capture finished after a glasses drop with a late frame.** "capture damage hallway left wall" said as the glasses lost power was saved 6.8 s later, after the reconnect, so the frame may not show what was asked for. The capture loop now stops when the walk leaves ACTIVE: *"Capture not taken. Say it again when the glasses are back."* Re-tested: power-off 0.5 s into a nameplate capture → abandoned at the phase change; the next capture works.
2. **Heat and battery were silent.** Overheat raised `THERMAL_CRITICAL` and the mock set 5% battery, but the app only changed a banner a tech wearing glasses never sees. New `GlassesController.alerts` speaks heat, battery (≤ 15%) and power-limit warnings, at most once a minute per message. Re-tested: both spoken; a second overheat within the minute stayed quiet.
3. **Slow reconnect after the glasses go back on.** The reconnect loop slept through its back-off (up to 10 s) even when the glasses became wearable. It now wakes on the not-ready → ready change (connected, unfolded, on). Re-tested: retry 1 ms after `don`, ACTIVE 0.23 s later (was 3.7 s).
4. **Back office 500 (Schemathesis).** An `item` of valid JSON that isn't an object (`"0"`) raised AttributeError. Now 400.
   - My first fix also rejected list `meta`, which **would have dropped every real event-log upload**. The existing record test caught it (disconnects 0 ≠ 1).
   - The final rule: `item` is an object, and `meta` is an object, except the event log, which is a list of objects. A list with a non-object would have crashed the record page later.
   - 5 parametrized regression cases. 400/404 are now documented in the OpenAPI.
   - The re-fuzz on a throwaway server (extraction off): **1,759 requests, 0 failures**.

### Checked, not bugs
- The back office page shows "QC FAIL CUT_OFF" correctly; it only looked wrong through PowerShell's JSON flattening.
- Gibberish is ignored silently, which is right for a hands-free app that shouldn't nag.
- The second "Label is cut off" prompt can still play in the 5 s before a link loss is declared. That's left as is: a shorter grace would abandon captures on brief blips.

### Mock findings (RESEARCH §5a)
- `fold()` also doffs, and only unfold + don makes the glasses eligible again.
- Thermal CRITICAL raises a session error but keeps streaming.
- Battery 5% raises nothing.

### Verified at the end
- Tests: app JVM, quality-core, and backend 17/17.
- `tools/phone_smoke.ps1` **PASS** on the new build and the restarted :8002 back office (14 earlier jobs intact): plate read correctly at $0.038 for the job, schema valid.
- Phone left as filming needs: Start job showing, Dataset off, feed = back camera, dataset and debug folders empty, "stay awake while charging" back to off.
