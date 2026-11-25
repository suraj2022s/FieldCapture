# Research notes

> These are the raw findings behind [PLAN.md](PLAN.md) and the future research memo (`docs/MEMO.md`).
> Status markers: **[doc]** = Meta documentation, **[sample]** = Meta's own sample code, **[issue]** = public issue tracker report (real hardware), **[verify]** = still to measure on our Mock Device Kit build, **[ours]** = measured by us (added later).
> Collected 2026-11-25.

## 1. DAT SDK, current state

- **Versions.** 1.0.0 (2026-09-24) is the stable release after a year of developer preview; Meta's FAQ says rollout starts 2026-09-30. 1.0.1 (2026-10-09) fixed HEVC buffer corruption and stream stutter. **1.1.0 (2026-11-20)** is the current release. [doc: CHANGELOG]
- **Distribution.** Maven Central, group `com.meta.wearable`, all artifacts share one version, and no token is needed. [doc]
  - Public modules: `mwdat-core`, `mwdat-camera`, `mwdat-display`, `mwdat-mockdevice`.
  - Experimental modules: `mwdat-inputs`, `mwdat-motion`, `mwdat-speech`.
- **Toolchain** (the CameraAccess sample's versions): AGP 8.11.1, Kotlin 2.2.21, Gradle 8.14.1, compileSdk 36, minSdk 31, JDK 17, Compose BOM 2026.05.01. [sample]
- **Experimental capabilities** can be built and tested but not published: Inputs, Motion (IMU), on-device Speech transcription, Voice invocations, standalone Photo capture (`camera.photo`), and camera Audio streaming (PCM alongside video). [doc: CHANGELOG, skills]
- **Developer Mode.** Manifest placeholders `mwdat_application_id = 0` and `mwdat_client_token = 0`. Enable it per device in the Meta AI app; it may reset after app or firmware updates. Production uses Wearables Developer Center credentials and release channels, and app submissions are not open yet. [doc, FAQ]

### 1.1 Lifecycle

- **Session states.** `DeviceSessionState`: `IDLE → STARTING → STARTED ⇄ PAUSED → STOPPING → STOPPED`, and `STOPPED` is terminal. `start()` and `stop()` are fire-and-forget, so failures come through `session.errors`. [doc]
- **New in 1.1.**
  - `DeviceSession.pause()` / `resume()` (calls outside STARTED or PAUSED are no-ops).
  - `DeviceSessionError.NOT_REGISTERED` from `createSession`.
  - `RegistrationError.UNAUTHORIZED`.
  - Unregistering stops every session.
  - When the glasses pause a stream, `Stream.errorStream` now emits `HINGE_CLOSED`, `THERMAL_HOT`, `BATTERY_LOW` or `PEAK_POWER_LIMIT`. Before 1.1, taking the glasses off reported `CRITICAL_STREAM_ERROR`. [doc: CHANGELOG]
- **Capabilities.** Attach once at `STARTED`. A second `addCamera` fails with `CAPABILITY_ALREADY_ADDED`. Calling it while `IDLE` gives `SESSION_IDLE`; after `STOPPED` it gives `SESSION_ALREADY_STOPPED`. [doc]
- **Stream states.** `STOPPED → STARTING → STARTED → STREAMING → STOPPING → STOPPED`, with `CLOSED` terminal. `PAUSED` comes from the device (for example a single cap-touch tap) and can resume on its own. `STREAM_ERROR` is informational; `CRITICAL_STREAM_ERROR` means tear down. [doc]
- **When the SDK pauses or stops a session.** Another experience takes the device; the glasses are removed or folded; Bluetooth drops; the app is unregistered or access is revoked. [doc]
- **Device state.** `batteryLevel`, `chargingState`, `donState`, `hingeState` and `thermalLevel` on `Device`, through `Wearables.devicesMetadata[id]`. Also `linkState` (CONNECTING, CONNECTED, DISCONNECTED) and `compatibility`. [doc]

### 1.2 Camera

- **Stream quality.** `VideoQuality.HIGH` 720×1280, `MEDIUM` 504×896, `LOW` 360×640. Frame rates: 2, 7, 15, 24 or 30 fps. "Lower resolution and frame rate usually produce better visual quality per frame over Bluetooth" (adaptive bitrate). [doc]
- **Frame format.** Frames arrive decoded as YUV by default. With `compressVideo = true` they arrive as HEVC buffers (`isCompressed`, `isCodecConfig`). [doc]
- **`stream.capturePhoto()`.** Works only while STREAMING, with one capture in flight at a time (`CaptureError.CaptureInProgress`). It returns a sealed `PhotoData.Bitmap` or `PhotoData.HEIC` and takes no size or quality arguments. [doc, sample]

### 1.3 Mock Device Kit

| Area | API |
|---|---|
| Setup | `MockDeviceKit.getInstance(ctx).enable(MockDeviceKitConfig(initiallyRegistered, initialPermissionsGranted, isDevMode))`, then `pairGlasses(GlassesModel.RAYBAN_META)` |
| Device state | `powerOn/Off`, `don/doff`, `fold/unfold`, `setBatteryLevel`, `setChargingState`, `setThermalLevel` |
| Camera | `services.camera.setCameraFeed(Uri \| CameraFacing)`, `setCapturedImage(Uri)` |
| Photo (standalone) | `services.cameraCapture.setCapturedPhoto` / `simulateCaptureFailure` |
| Speech | `services.speech.setTranscriptionSource(INJECTED \| LIVE_DEVICE_ASR)`, `simulateTranscription(text, isFinal, conf)`, `simulateError`, `simulateCompletion` |
| Motion | `services.motion.setMotionFeed(samples \| csvUri, loop)` |
| Inputs | `services.input` (inject input events) |
| Permissions | `permissions.set/setRequestResult` |
| Registration | `setRegistrationResult(success)` |
| Update gates | `setDATAppUpdateRequirementEnabled`, `setFirmwareUpdateRequirementEnabled` |
| Agent control | `startTestServer(port)` for the **MockDevice MCP** |

Sources: doc, CHANGELOG, and `samples/BirdSpotter/.../DatMockDeviceRepository.kt`.

- **Feed formats.** Video feeds must be **H.265**, because Android doesn't transcode and an h.264 file is rejected at stream start. Use `ffmpeg -i in.mp4 -c:v libx265 -tag:v hvc1 -an -vf "scale=540:960" out.mp4`. Images can be JPEG or PNG. [doc]
- **Phone-camera feed.** `setCameraFeed(CameraFacing)` needs the *Android* `CAMERA` runtime permission. That is not the DAT `Permission.CAMERA`; without it the mock rejects the stream start. [doc]
- **Display glasses.** MDK doesn't support display glasses for camera work, apart from a mock display preview. [doc: FAQ]
- **MockDevice MCP** (bundled in the `mwdat-android` Claude Code plugin). It drives the simulated glasses through the app's in-app test server, which starts with `startTestServer` and the `mwdat_ui_testing` intent extra. Feeds must be bundled in the app's `assets/`. [doc: skill `mockdevice-mcp`]

### 1.4 AI tooling Meta ships

- **Claude Code plugin.** `claude plugin marketplace add facebook/meta-wearables-dat-android`, then `claude plugin install mwdat-android@mwdat-android-marketplace`. It provides skills for getting-started, session-lifecycle, camera-streaming, mockdevice-testing, mockdevice-mcp, speech, motion, debugging and more, plus the docs MCP (`https://mcp.developer.meta.com/wearables`, `search_dat_docs`) and the MockDevice MCP. [doc]
- **Static fallbacks.** `AGENTS.md` and `llms.txt` (`https://wearables.developer.meta.com/llms.txt`). [doc]

## 2. Behaviour the docs leave out (Meta's own sample code)

All of these come from comments in `samples/BirdSpotter/app/src/main/java/.../data/dat/DatGlassesSessionRepository.kt` (DAT 1.1.0). Each one needs verifying on our mock build; the result goes in the memo as **[ours]**.

1. **`AutoDeviceSelector` races.** One built at the moment of the tap is still empty, so `createSession` fails `NO_ELIGIBLE_DEVICE`. Name the device with `SpecificDeviceSelector` instead. [sample] [verify]
2. **STARTED doesn't mean the device is reachable.** Capabilities other than the camera (Motion, Inputs, Speech) fail with `DEVICE_DISCONNECTED` if they're attached before the first frames arrive, and they don't retry. Attach them after the stream reaches STREAMING. [sample] [verify]
3. **Raw streams stop in the background.** A raw (YUV) stream is paused when the app leaves the foreground, but a compressed HEVC stream keeps flowing. This is critical for a phone in a pocket. [sample] [verify]
4. **A photo transfer knocks out other capabilities.** Motion, Speech and Inputs go to STOPPED while a still crosses the link, and nothing in the SDK restarts them. The app must revive them. [sample] [verify]
5. **Photo timing.** `stream.capturePhoto()` is published as taking about 1 s. Meta's sample backstops it at 15 s and fails any pending capture when the link drops. [sample]
6. **Speech results come through a `StateFlow`.** Equal consecutive results (the same short word twice, or a repeated stable partial) are deduplicated before the app sees them. [sample]
7. **On-glasses speech carries only text over the link,** whereas glasses-mic audio crosses the camera stream as PCM. That makes DAT Speech the bandwidth-friendly route once it can be published. [sample]
8. **The mock camera has no audio.** It plays a file or the phone camera, so audio streaming on the mock is silent at best. The phone mic is the honest answer on a mock pair. [sample + skill `audio-streaming`]
9. **The state flow replays STOPPED.** Camera, Inputs and Speech state flows replay `STOPPED` (or `INACTIVE`) to new collectors before start. Only act on a stop that comes *after* a start. [sample]

## 3. What breaks on real glasses (public issue tracker, `meta/meta-wearables-dat-android`)

| Issue | Finding | Why it matters for field estimation |
|---|---|---|
| #140 | DAT stream gives about **53° horizontal FOV**, against about 88° for native capture on the same hardware (open) | Less context per frame. Damage size and room context suffer; nameplates get more pixels on target. |
| #165 | "Why does the resolution change?" Adaptive bitrate. | Frame resolution isn't stable, so QC must normalise its working size |
| #155 | Stream is H.265 with parameter sets in-band in the first keyframe (undocumented); MediaMuxer aborts on the wrong assumption | Matters if we decode compressed frames ourselves (pocket mode) |
| #159, #206, #196, #181 | Camera FPS drops, the session ends, or the mic is silent when the glasses mic (HFP/SCO) is used during a camera stream. Wi-Fi transport for Android was requested. | Voice and camera compete for Bluetooth. This is why we use the phone mic or DAT Speech, never HFP. |
| #174 | Request for Wi-Fi Direct to get higher bitrate "for text and screen understanding" | Small text (serial numbers) is bitrate-limited |
| #162 | Stream choppy with a 5–15 s delay; force-stopping Meta AI fixes it | Latency spikes; an app-level watchdog is needed |
| #199, #215 | Slow photo delivery (> 40 s for a 1.5 MB HEIC); `capturePhoto` taking > 3.5 s ended the session (both closed) | Photo latency varies; serialise captures and time them out |
| #198 | `PhotoResolution.FULL` gives unexpected orientation and less vertical content than a physical-button photo | Check orientation and EXIF handling |
| #237 | MockDeviceKit: `DeviceSession.start()` sometimes stays STARTING with no error (open) | A STARTING watchdog is required |
| #227, #233, #192, #223, #247 | Sessions refused with `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` or `SESSION_ENDED_BY_DEVICE` on some firmware | Firmware/app version skew is the #1 field failure → surface clear update prompts |
| #208, #150 | BLE data link drops and stays down; SDK link can't connect while Meta AI holds the BLE link | A reconnect supervisor is needed; some states need a glasses restart |
| #220 | Native crash after closing a session while folding the temple | Avoid stop/fold races; test fold during stop |

Third-party note: the GemGlasses README reports **"the first frame takes ~5 s"** on real glasses after starting a camera session. [verify on the mock; real-hardware number is theirs]

## 4. Similar projects

### 4.1 Same brief (public, both created 2026-11-24)

- **`vaibhavgupta856/FieldCapture`** (Kotlin, 5 commits)
  - Stack: Android + DAT 1.1.0 + MDK phone camera, ML Kit OCR + Gemini Flash, WorkManager queue, 14 line items.
  - The README says it is "scaffolded… accuracy numbers, demo video and the three sample job records are **not** included". Its quality thresholds are "seed values, not measured", and there is no backend server.
  - Setup requires tapping through 4 buttons before a walk.
  - It was built with OpenCode.
- **`AgrimaBhatnagar/FieldCapture`** (Python, 2 commits)
  - A browser prototype using PaddleOCR + Tesseract, plus a Random Forest that only detects whether damage is present.
  - Its iOS DAT layer is an *uncompiled scaffold*: "next engineering stage is SDK integration, native compilation…".
  - It claims 92% OCR exact field and 90% damage-presence accuracy, but its eval is unclear.
- **Verdict:** neither can be "directly implemented and improved" without inheriting unmeasured or uncompiled work, and copying would fail the "explain your own code" requirement. We build our own and exceed both on measured evidence.

### 4.2 Related DAT projects

- **Meta `samples/BirdSpotter`** (official, DAT 1.1.0) is the best reference architecture. It shows failover audio (glasses → phone mic), capability revival, mock-device control and diagnostics. We borrow patterns but not code. The DAT repo is under Meta's Wearables Developer Terms.
- **`hatchettc84/GemGlasses`** (fork of `lpecom/GemGlasses`): an Android Gemini Live assistant with on-demand vision bursts and a token backend. It reports a ~5 s first frame.
- **`SameelNawaz/meta-DeviceAccessToolkit`**: a JS proof of concept that simulates MDK in a browser, with a Kotlin bridge stub.
- **`straff2002/OpenGlasses`**: an iOS assistant (141★) with a "Field Assist" professional tier and job recording.
- **`Intent-Lab/VisionClaw`**: an always-on agent on Ray-Ban Meta with Gemini Live (arXiv 2604.03486).

### 4.3 Commercial landscape (context, not used)

- **Photo-to-estimate tools:** Encircle Scope (photos, moisture readings and equipment → S500-aligned scope), Axiom (drafts mitigation estimates from photos and notes, exports to Xactimate, and itself notes that photos can't confirm moisture), Ontrakt, and QuoteIQ.
- **Hands-free wearables:** RealWear HMT + IBM Inspector Wearable (voice control, noise-robust, gloves) and Zuper Glass.
- **The gap none of them fill:** consumer AI glasses with an offline-first, quality-gated capture loop.

## 5. Open questions to measure on our build (→ memo "[ours]")

- [ ] Time to first frame after `session.start()` on MDK, phone-camera feed vs file feed
- [ ] Actual frame size and fps delivered per `VideoQuality` / `frameRate` on MDK
- [ ] `capturePhoto()` on MDK: latency, resolution, and whether it is the `setCapturedImage` image or a frame from the feed
- [ ] Does MDK reproduce the raw-stream pause when the app is backgrounded (finding 2.3)?
- [ ] Which stream errors and states MDK emits for doff, fold, power-off, thermal and battery
- [ ] Reconnect time after `powerOff()` → `powerOn()`
- [ ] Does MDK Speech `LIVE_DEVICE_ASR` work without Developer Center capability approval?
- [ ] Nameplate serial accuracy at 504×896 vs 720×1280 stream frames vs stills

## Sources

- Docs: [Android integration](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android/) · [Mock Device Kit](https://wearables.developer.meta.com/docs/develop/dat/mock-device-kit/) · [Testing with MDK on Android](https://wearables.developer.meta.com/docs/develop/dat/testing-mdk-android/) · [FAQ](https://developers.meta.com/wearables/faq/) · [AI-assisted dev (Claude Code)](https://wearables.developer.meta.com/docs/develop/dat/ai-assisted-claude-code/) · [llms.txt](https://wearables.developer.meta.com/llms.txt)
- Repo: [meta/meta-wearables-dat-android](https://github.com/meta/meta-wearables-dat-android) (CHANGELOG, AGENTS.md, `samples/CameraAccess`, `samples/BirdSpotter`, `plugins/mwdat-android/skills/*`) · [issues](https://github.com/meta/meta-wearables-dat-android/issues)
- News: [Mixed News on DAT 1.0 experimental features](https://mixed-news.com/en/expo-meta-wearables-dat-1-0-wrapper-experimental-cannot-publish/)
- Similar projects: [vaibhavgupta856/FieldCapture](https://github.com/vaibhavgupta856/FieldCapture) · [AgrimaBhatnagar/FieldCapture](https://github.com/AgrimaBhatnagar/FieldCapture) · [hatchettc84/GemGlasses](https://github.com/hatchettc84/GemGlasses) · [SameelNawaz/meta-DeviceAccessToolkit](https://github.com/SameelNawaz/meta-DeviceAccessToolkit) · [straff2002/OpenGlasses](https://github.com/straff2002/OpenGlasses) · [VisionClaw README](https://raw.githubusercontent.com/Intent-Lab/VisionClaw/main/README.md)
- Commercial: [Axiom for water damage](https://buildaxiom.ai/for/water-damage-restoration) · [Ontrakt overview](https://ontrakt.com/blog/water-damage-restoration-software) · [QuoteIQ AI tools list](https://myquoteiq.com/top-10-ai-tools-for-water-damage-restoration-businesses-in-2026/) · [RealWear field services](https://legacy.realwear.com/solution-field-services/) · [IBM Inspector Wearable](https://www.ibm.com/products/inspector-wearable) · [Zuper Glass](https://www.contractingbusiness.com/product-news/product/55332970/zuper-glass-delivers-voice-activated-control-for-field-teams)
