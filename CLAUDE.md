# FieldCapture: context for Claude Code sessions

**Start every session by reading, in order:**
1. `docs/JOURNAL.md`. The latest entry says where we are and what's next.
2. `docs/PLAN.md`. Milestones with checkboxes, architecture, decisions.
3. `docs/RESEARCH.md`. DAT SDK facts, undocumented behaviour, real-glasses issues, competitors.

The original brief is in `private/BRIEF.md` (local only, gitignored). The requirement checklist is in PLAN.md §1.

## What this is

A take-home assignment: build **FieldCapture**, an Android app on Meta's Wearables Device Access Toolkit (DAT). It runs on Mock Device Kit instead of real glasses and turns a technician's voice-driven walk-through into a structured JSON job record plus draft estimate line items.

- **Scoring (100):** works end-to-end 20 · extraction & evaluation with honest numbers 20 · SDK depth 15 · research memo & hardware judgment 15 · capture quality 10 · product judgment 10 · code & README 10.
- **Timing:** brief received 2026-11-25 (assumed; update if the user corrects it). Deadline is 72 h after receipt. Target effort is ~20 h.
- **Hard rules from the brief:**
  - No fake data. Every frame in the test set and demo comes from our own captures.
  - Don't film people, or property without permission.
  - Spend at most $50 on APIs and parts; keep receipts.
  - The demo video must come from the submitted build (tag `demo-v1`).
  - Disclose what doesn't work.
- **Live session after submission:** add a 4th voice command end-to-end, run a failure drill on our worst failure, and a hardware Q&A. Keep code small and explainable.

## Working rules (the user's standing instructions)

1. **Journal everything.** After every meaningful step, append an entry to `docs/JOURNAL.md`: what was done, the decision and why, how it was verified, and the next step. Tick the matching box in `docs/PLAN.md`.
2. **Commit and push as you go.** Reviewers read the history. Use small, descriptive commits. Never commit secrets (`.env`, `local.properties`, API keys, `private/`).
3. **Measure, don't assert.** Every number in the docs comes from a script plus committed data.
4. **Flag limitations** in the docs instead of hiding them.
5. **Ask before** installing things outside the repo (Claude Code plugins, global tools), spending API money on large runs, or making the repo public.
6. The user prefers research-backed choices: check prior art and current docs before building.

## Locked decisions (rationale in PLAN.md §3)

- **Android stack:** Kotlin + Jetpack Compose. **DAT 1.1.0** from Maven Central (`com.meta.wearable:mwdat-*`). minSdk 31, compileSdk 36, AGP 8.11.x, Kotlin 2.2.x, Gradle 8.14.x (match `samples/CameraAccess`).
- **Glasses stand-in:** Mock Device Kit fed by the phone camera (`setCameraFeed(CameraFacing.BACK)`), or by HEVC files for repeatable runs.
- **Voice:** phone mic → Android on-device `SpeechRecognizer`. DAT Speech is experimental, so it sits behind a flag and in tests (MockSpeechKit INJECTED).
- **Backend:** Python 3.12 + FastAPI + SQLite, managed with `uv`. Claude is called through the official `anthropic` Python SDK. The model per task is chosen by our eval among Haiku 5.5, Sonnet 5.5 and Opus 5.5; Opus 5.5 is the default until measured. The user approved benchmarking all three.
- **Stretch goals:** size without depth, plus cost & latency.
- **GitHub:** `suraj2022s/FieldCapture`, **private** until submission. At submission, make it public or add the reviewers, and ask the user first.

## Repo layout (planned; update as it lands)

```
android/        Gradle project (app/, quality-core/)  — open this folder in Android Studio
backend/        FastAPI ingest + extractors + line-item rules + job assembler + back-office page
schemas/        JSON Schemas + line-item code table
eval/           test-set frames + labels, scripts, cached model responses, REPORT.md
samples/        3 real job records
tools/          ffmpeg transcode, labeling page, chaos scripts
docs/           PLAN, JOURNAL, RESEARCH, MEMO
private/        local-only (brief) — gitignored
```

## Commands

Not created yet. Fill this in when the Gradle project and backend exist (M1/M3).

## DAT SDK gotchas (details and sources in docs/RESEARCH.md)

**Session**
- `session.start()` is fire-and-forget. Collect `state` and `errors` before calling it.
- Add the Camera once, at `STARTED`. `PAUSED → STARTED` keeps capabilities attached.
- `STOPPED` is terminal; create a new session.
- Use `SpecificDeviceSelector`. `AutoDeviceSelector` races and fails with `NO_ELIGIBLE_DEVICE`.

**New in 1.1**
- `DeviceSession.pause()` / `resume()`.
- Stream errors `HINGE_CLOSED`, `THERMAL_HOT`, `BATTERY_LOW`, `PEAK_POWER_LIMIT`.
- `createSession` fails with `NOT_REGISTERED` before registration.

**Camera**
- `stream.capturePhoto()` works only while STREAMING, with one capture in flight. It takes about 1 s; back it with a 15 s timeout.
- A photo transfer stops Motion, Speech and Inputs. Revive them afterwards.
- Raw YUV streams pause when the app is backgrounded, but compressed HEVC keeps flowing. So pocket mode needs `compressVideo = true` plus our own MediaCodec decode (verify on MDK).
- Stream sizes: HIGH 720×1280, MEDIUM 504×896, LOW 360×640. Frame rates: 2, 7, 15, 24, 30 fps.

**Mock Device Kit**
- Video feeds must be H.265: `ffmpeg -i in.mp4 -c:v libx265 -tag:v hvc1 -an -vf "scale=540:960" out.mp4`.
- The phone-camera feed needs the *Android* `CAMERA` runtime permission.
- MDK has no glasses audio. `services.speech.setTranscriptionSource(LIVE_DEVICE_ASR)` uses phone ASR; `INJECTED` + `simulateTranscription()` is for tests.
- An MDK session can hang in STARTING (issue #237). Use a watchdog and recreate the session.
- Debug builds should call `startTestServer` (via the `mwdat_ui_testing` extra) so the MockDevice MCP can drive the simulated glasses.

**Experimental (can't be published)**
- Inputs, Motion, Speech, Voice invocations, standalone Photo, camera Audio streaming.

## Environment (this machine)

- Windows 11, with PowerShell 5.1 and Git Bash.
- Android Studio (Narwhal, JBR 21) is at `C:\Program Files\Android\Android Studio`.
- **The Android SDK is not installed yet.** `%LOCALAPPDATA%\Android` is empty. Install platform 36, build-tools and platform-tools in M1.
- Installed: `uv` (use uv-managed Python 3.12; system Python is 3.10), Node, ffmpeg, `gh` (authenticated as `suraj2022s`), git.
- Not available: docker; `adb` isn't on PATH until platform-tools is installed.
- The user runs the app on an **Android phone** and records all test captures themselves.

## Tools used (keep this current for the README's "AI tools" section)

- Claude Code (Claude Fable 5.2 / Opus 5.5) for research, competitor scan, planning, code and docs.
- Meta's `mwdat-android` Claude Code plugin (skills, docs MCP, MockDevice MCP). Planned for M1; install only with the user's OK.
