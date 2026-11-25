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
