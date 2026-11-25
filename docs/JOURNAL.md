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
