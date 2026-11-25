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
