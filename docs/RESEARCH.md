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

### 4.3a Refresh (2026-11-27)

- **Same-brief repos:** both were last pushed on 2026-11-24 and are unchanged since §4.1 (`gh api`). A GitHub search for newer DAT field-capture projects found one adjacent project.
  - **`sandrobl/hands-free-incident-report-smartglasses`** records DAT video and audio.
  - It encrypts the payload (AES session key wrapped with RSA) and uploads it with GPS and compass heading, behind Auth0 login.
  - It does no extraction. It's the useful contrast for our weakest production gap: our uploads have **no auth and no encryption**, because they go over USB to a local back office.
- **Nameplate scanners** (ServiceTrade Smart Scan, Parts Town data-plate scanner, ACLogics, measureQuick):
  - All are phone-photo apps (hands busy).
  - **None publish an accuracy figure**; the claims are marketing.
  - Several add **serial → manufacture-date decoding** per brand (HVAC Decoder, SerialCheck). We report a date only when it's printed.
- **Photo-to-estimate:**
  - Verisk ships XactGen (photos + measurements → Xactimate estimate), XactGen Capture (policyholder room photos → measurements and sketch), XactAI (photo naming) and the XactRestore AI Estimator.
  - They use **real price lists and room sketches**; we use a small rules table and single-photo sizing.
  - Industry coverage still calls photo-to-line-item suggestion "maturing".
- **Meta glasses at work:**
  - TruVideo (auto service) starts and stops inspection recordings by voice on Meta AI glasses.
  - ACES Pest Control uses them for hands-free documentation.
  - Both record video; neither extracts structured fields at capture time.
- Sources:
  - [ServiceTrade Smart Scan](https://fireandsafetyjournalamericas.com/servicetrade-launches-ai-powered-smart-scan/)
  - [Parts Town scanner](https://www.phcppros.com/articles/19769-parts-town-launches-innovative-hvac-data-plate-scanner)
  - [ACLogics](https://play.google.com/store/apps/details?id=com.aclogics.app)
  - [SerialCheck](https://apps.apple.com/us/app/-/id6759895640)
  - [XactGen](https://www.xactware.com/xactgen.html)
  - [XactGen Capture webinar](https://www.verisk.com/resources/webinars/on-demand/2026/faster-interior-water-settlements-with-xactgen-capture/)
  - [XactAI](https://www.verisk.com/blog/xactai-built-from-your-insights-delivered-through-your-workflows/)
  - [TruVideo + Meta glasses](https://techedgeai.com/truvideo-brings-meta-ai-glasses-into-auto-service/)
  - [ACES Pest Control](https://www.trendhunter.com/trends/aces-pest-control-meta)

### 4.4 More findings while building (2026-11-25)

- **MediaTek hardware HEVC decoder is blocked by Meta.** Meta's own CameraAccess decoder (`samples/CameraAccess/.../stream/HevcDecoder.kt`) blocks `OMX.Exynos.hevc.dec` and **`c2.mtk.hevc.decoder`**. The reason given is that they "corrupt this stream into fragments/trash; the SDK's own decoder skips them too". Our test phone (Dimensity 6300) is MediaTek, so decoding falls back to a software decoder (CPU cost on a 4 GB phone). [sample]
- **The raw frame format is undocumented.**
  - What is documented: `VideoFrame(buffer, width, height, presentationTimeUs, isCompressed, isCodecConfig)`, and the docs say only "YUV pixel data".
  - What is missing: the plane layout (I420, NV12 or NV21) and the row stride. The internal `VideoDecoder` configures a MediaCodec `color-format`, but nothing public exposes it. [ours: javap of mwdat-camera 1.1.0]
  - How we'll settle it: the app logs bytes vs `w*h*1.5` for the first frame, and writes the frame under each candidate layout.
- **The public API surface was confirmed with `javap`** against the 1.1.0 AARs from Maven Central.
  - `Stream.capturePhoto()` returns `DatResult<PhotoData, CaptureError>`.
  - `DeviceSessionError` has 19 values, including `DWA_UNAVAILABLE` and `UNEXPECTED_ERROR`.
  - `StreamError` adds `PERMISSIONS_DENIED` and `TIMEOUT`.
  - Mock Device Kit additions: `MockDevice.setBatteryLevel/setChargingState/setThermalLevel`, `MockSpeechKit.setTranscriptionSource(INJECTED | LIVE_DEVICE_ASR)`, and `getLiveSourceDiagnostic()`.
- **Tooling change.** As of command-line tools 16111833, `sdkmanager` is deprecated and forwards to a new "Android CLI". It collects usage metrics unless you pass `--no-metrics`, and it uses slash package names (`platforms/android-36`). Not DAT-specific, but it affects "setup in under 10 minutes".

## 5a. Measured on our build (OPPO A6x 5G, Android 16, DAT 1.1.0 Mock Device Kit) — [ours]

All measured 2026-11-25 from the app's event log (logcat tag `FieldCapture`).

| What we checked | Result |
|---|---|
| Registration without the Meta AI app | Mock Device Kit simulates registration and permissions: `REGISTERED` and DAT camera permission `Granted` on a phone **without** the Meta AI app installed. Real glasses need Meta AI. |
| Session start | `createSession` → `STARTING` → `STARTED` in **47–54 ms**. Camera `STARTED` follows immediately; stream `STARTING` → `STREAMING` takes **1.5–2.1 s**. |
| Time to first frame (after `createSession`) | **1,777 ms and 2,609 ms** (n=2, phone-camera feed). |
| Requested vs delivered stream | We asked for `VideoQuality.HIGH` (720×1280) at 15 fps. The phone-camera feed delivered **480×640 at ~29.6 fps**. The mock ignores both requested quality and frame rate, so mock frames are *smaller* than real HIGH frames, and small-text accuracy on the mock is a pessimistic bound. |
| Raw frame format | **I420 (planar Y, U, V), tightly packed, stride = width**: 460,800 B = 480×640×1.5. Verified by rendering the same frame as I420, NV21 and NV12; only I420 has natural colours. The format is not documented. |
| `capturePhoto()` on the mock | Phone-camera feed: a **480×640 HEIC in 656 ms**, the same resolution as the stream, so no detail gain. Video-file feed with no `setCapturedImage`: a **1×1 HEIC placeholder (759 B) in 45 ms**. Treat mock stills as untrusted; the app now rejects stills under 120 px. |
| `setCameraFeed(Uri)` while streaming | Did **not** switch the live stream; frames kept coming from the phone camera. Set the feed before starting the stream, or restart the stream. |
| On-device speech | On this phone `SpeechRecognizer.isOnDeviceRecognitionAvailable` is true, but **en-US is not installed** on the on-device engine. The app requests the model (`triggerModelDownload`, API 33+) and falls back to Google's recognition service (`GoogleTTSRecognitionService`, the system default). |
| Recognizer confidence score | Google's service reports **0.00 for every result** on this phone, so 0 must be read as "no score" rather than "wrong". `ERROR_CLIENT` follows almost every result: a restart race, not a failure. |
| First natural voice walk (user, 6 captures) | **Speech start → command understood: 2.6–5.1 s** (includes speaking time). **Command → durable save: 0.6–1.2 s**, including `capturePhoto()` at 544–802 ms (480×640 HEIC, 70–110 KB). **Upload to the back office: <0.3 s** over USB. One dark capture got 2 "Too dark" prompts, then the app accepted a good frame by itself after 4.2 s. |
| How a first-time user phrases commands | Every command was natural: "**Please** capture the broken wall", "capture the seepage on wall", "capture the floor is not good". No room was ever named. This drove a forgiving parser and a one-time room tip. |
| Instrumented lifecycle tests | **8/8 pass on device**: start/stop, pause/resume, power loss → reconnect same walk, overheat, doff/don, fold/unfold, outdated glasses app, voice → durable queued capture. |
| Mock `doff()` | Does **not** pause the stream (it keeps STREAMING), unlike real glasses per the 1.1 changelog. The app therefore suspends capture from the device's `donState`/`hingeState`. |
| Video-file feed (our 8 s, 540×960, 24 fps H.265 clip) | Delivered at **540×960, 15.0 fps** (435 frames in ~29 s, requested 15 fps), and frames **kept flowing past the clip's end**; we didn't check whether it loops or holds the last frame. So the mock honours the requested frame rate for a file feed but not for the phone-camera feed (~30 fps). `capturePhoto()` again returned the 1×1 placeholder (21 ms). |
| App killed mid-walk (OS kill / reinstall) | Before 2026-11-25 evening, a killed walk stayed open forever on the phone: no `finalize`, and the next "start job" opened a new job id. Both of the user's real jobs from the afternoon were still open. The app now resumes the most recently active open job on launch if it saw activity in the last 30 min (counts, room and timeline restored) and closes older ones. Verified on the real job `5482e66c`: resumed with 6 captures; the other job was closed and finalized. |
| Video-file feed playback speed | The mock delivers **one source frame per stream frame**, at the stream's rate. Our 24 fps clip streamed at 15 fps played at **15/24 = 0.625× real time**: a 10 s segment lasted 16 s. It matches the frame luma at three points of a 240 s rehearsal clip (2026-11-26) and explains the earlier "8 s clip keeps flowing past 8 s" (192 frames ÷ 15 fps = 12.8 s). **Encode mock clips at the stream fps** for real-time playback. Not documented. |
| `checkPermissionStatus(CAMERA)` with the glasses off | Returns a **failure**, not `Denied`: "All discovered devices are powered off or disconnected" (chaos run, 2026-11-25). A permission gate must treat a failed check as "unknown", not "denied", or a reconnect loop would ask the tech to grant a permission they already granted. |
| Reconnect after mock `powerOff()` → `powerOn()` | Link back → new session → STREAMING in **~0.2–0.3 s** on the mock (first frame 304 ms after `createSession`). Our 5 s link-loss grace dominates the outage the tech hears. Real glasses will be slower (Bluetooth re-pair, ~5 s first frame reported by GemGlasses). |
| Mock fold, overheat and battery mid-walk (UI test through the app's buttons, 2026-11-26) | **`fold()` also sets don to DOFFED**, and the device ends the session (`SESSION_ENDED_BY_DEVICE`). Every new session fails with `NO_ELIGIBLE_DEVICE` until the glasses are unfolded **and** put back on: `unfold()` alone leaves them DOFFED. **`setThermalLevel(CRITICAL)`** raises `DeviceSessionError.THERMAL_CRITICAL`, but the stream **keeps STREAMING**. **Battery 5%** changes only the battery level: no stream error, no session error. So on the mock, heat and battery never stop a walk, and an app that only reacts to stopped streams says nothing (ours did, until it gained spoken alerts). Not documented. |
| Screen off / app in the background mid-walk (2026-11-26) | With our camera-type foreground service, the stream **keeps STREAMING at full rate** with the screen off (power button) and with the app behind the home screen: test feed 15 fps, phone-camera feed ~30 fps. Captures said meanwhile are saved and uploaded. So Mock Device Kit does **not** reproduce the raw-stream pause in the background that Meta's BirdSpotter sample works around (finding 2.3). Re-check on real glasses before relying on pocket use. |
| Fast pause/resume (taps 0.1 s apart, 2026-11-26) | `resume()` called the moment the session reports PAUSED, before the **stream** reports PAUSED, or `pause()` on a session whose camera is still starting: Mock Device Kit **stops the camera** (stream STOPPED, then camera STOPPED), so the session has to be rebuilt. Our app did that in an endless loop while paused. The fix is to pause only when the stream is STREAMING and resume only once the stream is PAUSED (DAT docs: "call pause() or resume() again once it settles"). After a pause/resume, a **file feed runs at ~30 fps instead of 15** and its frames are **smeared** (HEVC decoding restarted without a keyframe; seen in the live view and in a saved capture) until the session is recreated. The phone-camera feed is clean after pause/resume (screenshot checked, 29.6 fps). So don't demo pause on a file feed. Not documented. |
| More glasses events through the test server (`tools/mdk_scenarios.ps1`, 2026-11-26) | **Touchpad tap**: the session goes PAUSED (device-initiated); a second tap brings it back to STARTED. **Tap-and-hold**: `SESSION_ENDED_BY_DEVICE`. **Camera permission set to denied**: the next session still reaches STARTED, then the *stream* fails with `PERMISSIONS_DENIED` and stays STOPPED; nothing retries it. `checkPermissionStatus` fails ("can't tell") while the glasses are off. **Firmware update required**: device `compatibility` becomes `DEVICE_UPDATE_REQUIRED`; the first session started and even streamed for ~15 s before `SESSION_ENDED_BY_DEVICE`, later ones failed with `NO_ELIGIBLE_DEVICE`. **Charging**: no effect on the session. **Unpair**: `CRITICAL_STREAM_ERROR` and the device disappears; a newly paired device starts folded and off. **Kit re-enabled unregistered**: registration `UNAVAILABLE`. None of this is documented as behaviour. |
| Phone speech recognition on the test phone (2026-11-26 evening, the user's real voice) | The on-device engine (`createOnDeviceSpeechRecognizer`, Android System Intelligence / SODA, en-GB and en-US packs installed) recorded the mic ("not silenced") but returned **no words and never a single `onRmsChanged`**. It reported "no speech detected" every 5 s, or one round sat "listening" for minutes. Switching to the default service (`GoogleTTSRecognitionService`, Speech Services by Google) understood the same voice at once ("Please end the job"). Every scripted test injected text, so none of them could see this. The app now falls back after 3 empty or 2 stuck rounds and remembers it. |
| Other emulators (web search, 2026-11-26) | Mock Device Kit is the only official way to run a DAT camera app without glasses. Meta's Ray-Ban Display Simulator (Chrome extension) only previews the display web apps. Google's Android XR AI-glasses emulator emulates Google's platform, not DAT. The open-source xg.glass simulator wraps an older DAT for Meta, with no Meta video. No cloud device lab was found. |
| MockDevice MCP on Windows | `mock_connect` needs `adb` on PATH (it ignores `ANDROID_HOME`). The app's test server can be driven directly over HTTP after `adb forward tcp:8237 tcp:8237`; `GET /capabilities` lists 55 routes (contract version 1). |

## 5b. Checked against Meta's DAT docs MCP (`search_dat_docs`, docs v1.1, 2026-11-25)

Which of our "[ours]" mock findings the docs already cover, so the memo only claims what is new:

| Our finding | What the docs say | Verdict |
|---|---|---|
| Raw frames are tight I420 | `VideoFrame.buffer` holds "decoded YUV pixel data"; no plane layout or stride (Android). iOS names `420v`. | **Not documented** for Android |
| Mock `doff()` keeps the stream STREAMING | Nothing on mock don/doff and stream state | **Not documented** |
| `setCameraFeed(Uri)` mid-stream is ignored | "*Before* starting a stream, you can configure Mock Device Kit with…" a feed | Implied, not stated |
| In-stream `capturePhoto()` gives a 1×1 placeholder with a video feed | Only covers standalone `MockCameraCaptureKit`: without `setCapturedPhoto` "the mock reports that no capture is available". The in-stream placeholder isn't mentioned. | **Not documented** |
| Phone-camera feed ignores requested quality/fps | Nothing | **Not documented** |

Docs facts we hadn't used yet:
- "The camera stays started while its stream is stopped… If the camera is started, call `start()` on its stream again. If it is stopping or stopped, add a new camera." Our app didn't handle a stream stop under a started camera; fixed on 2026-11-25.
- Mock Device Kit "does not model Stream and Photo hardware contention, and its transfer progress is not representative of a real device". Real-glasses photo timing (issues #199/#215) can't be judged on the mock; say so in the memo.
- `checkPermissionStatus` is a suspend function returning `DatResult`; check it before starting a stream.

## 5. Open questions to measure on our build (→ memo "[ours]")

- [ ] Time to first frame after `session.start()` on MDK, phone-camera feed vs file feed
- [ ] Actual frame size and fps delivered per `VideoQuality` / `frameRate` on MDK
- [ ] `capturePhoto()` on MDK: latency, resolution, and whether it is the `setCapturedImage` image or a frame from the feed
- [x] Does MDK reproduce the raw-stream pause when the app is backgrounded (finding 2.3)? **No** (§5a)
- [x] Which stream errors and states MDK emits for doff, fold, power-off, thermal and battery (§5a)
- [x] Reconnect time after `powerOff()` → `powerOn()` (§5a)
- [ ] Does MDK Speech `LIVE_DEVICE_ASR` work without Developer Center capability approval?
- [ ] Nameplate serial accuracy at 504×896 vs 720×1280 stream frames vs stills

## Sources

- Docs: [Android integration](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android/) · [Mock Device Kit](https://wearables.developer.meta.com/docs/develop/dat/mock-device-kit/) · [Testing with MDK on Android](https://wearables.developer.meta.com/docs/develop/dat/testing-mdk-android/) · [FAQ](https://developers.meta.com/wearables/faq/) · [AI-assisted dev (Claude Code)](https://wearables.developer.meta.com/docs/develop/dat/ai-assisted-claude-code/) · [llms.txt](https://wearables.developer.meta.com/llms.txt)
- Repo: [meta/meta-wearables-dat-android](https://github.com/meta/meta-wearables-dat-android) (CHANGELOG, AGENTS.md, `samples/CameraAccess`, `samples/BirdSpotter`, `plugins/mwdat-android/skills/*`) · [issues](https://github.com/meta/meta-wearables-dat-android/issues)
- News: [Mixed News on DAT 1.0 experimental features](https://mixed-news.com/en/expo-meta-wearables-dat-1-0-wrapper-experimental-cannot-publish/)
- Similar projects: [vaibhavgupta856/FieldCapture](https://github.com/vaibhavgupta856/FieldCapture) · [AgrimaBhatnagar/FieldCapture](https://github.com/AgrimaBhatnagar/FieldCapture) · [hatchettc84/GemGlasses](https://github.com/hatchettc84/GemGlasses) · [SameelNawaz/meta-DeviceAccessToolkit](https://github.com/SameelNawaz/meta-DeviceAccessToolkit) · [straff2002/OpenGlasses](https://github.com/straff2002/OpenGlasses) · [VisionClaw README](https://raw.githubusercontent.com/Intent-Lab/VisionClaw/main/README.md)
- Commercial: [Axiom for water damage](https://buildaxiom.ai/for/water-damage-restoration) · [Ontrakt overview](https://ontrakt.com/blog/water-damage-restoration-software) · [QuoteIQ AI tools list](https://myquoteiq.com/top-10-ai-tools-for-water-damage-restoration-businesses-in-2026/) · [RealWear field services](https://legacy.realwear.com/solution-field-services/) · [IBM Inspector Wearable](https://www.ibm.com/products/inspector-wearable) · [Zuper Glass](https://www.contractingbusiness.com/product-news/product/55332970/zuper-glass-delivers-voice-activated-control-for-field-teams)
