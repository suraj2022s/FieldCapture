# Research memo: Meta glasses for field estimation

What we measured: DAT 1.1.0 on Mock Device Kit (MDK), fed by an Android phone's camera. There were no real glasses. The test set is 204 frames of 22 real scenes in one home. Every number comes from a committed script ([eval/REPORT.md](../eval/REPORT.md)). Real-glasses behaviour is quoted from Meta's issue tracker and marked as such.

## What today's glasses can and can't do for this job

**They can:**
- **Capture hands-free on a spoken command, without losing anything.**
  - Command → durable save takes 0.6–1.2 s.
  - Three sample walks were uploaded over Wi-Fi with no cable.
  - A chaos run (back office down 60 s, app killed, glasses powered off) lost 0 items and duplicated 0.
- **Read what is legible, and stay silent about what isn't** (Opus 5.5 in the back office):
  - When it answers a plate field, it is right **92%** of the time (131 answered fields, 11 wrong).
  - It invented a value for **0 of 330** fields that aren't printed.
  - With the shipped confidence, **1 of the 11** wrong answers is auto-accepted.
- **Turn spoken notes into fields.** Category was right 18/18; location 72–83%.

**They can't, yet:**
- **Measure.** There's no depth sensor. Size from one photo misses the taped area by a **median 255%** (29 frames), or **505%** when scaling from objects in view. The model also sizes a different extent than the tape (a whole stained wall vs a 35 cm vent). Every size goes out as "measure".
- **Read small print reliably.** Opus leaves **149 of 280** printed plate fields blank, and in good light reads only **52%** of them exactly.
  - The DAT stream tops out at 720×1280 with a ~53° field of view (issue #140); MDK gave us 480×640.
  - So our numbers are a pessimistic bound. On real glasses the bitrate also adapts (#165).
- **See in the dark.** "Dark" is **34 of 70** bad frames. Attics, cupboards and crawl spaces are the job.
- **Listen over the same link.** The glasses mic (HFP) starves the camera stream (#159, #206), so we use the phone mic. Even so, real speech came back as "name blade" (nameplate), "c page" (seepage), "enter the job" (end the job), and dropped units. Parser fixes and a spoken confirmation for "end job" cover the costly ones.

## What the docs leave out (measured on our build)

- **Raw frames are tightly packed I420** (stride = width). The docs say only "YUV".
- **The MDK phone-camera feed ignores the requested quality and fps.** It delivers 480×640 at ~30 fps whatever you ask for. A file feed plays one source frame per stream frame, so a 24 fps clip runs at 0.625× real time.
- **Pause/resume has to settle.**
  - `resume()` before the *stream* reports PAUSED stops the camera, and the session must be rebuilt.
  - After a pause, a file feed runs at 2× and its frames are smeared.
- **Glasses events MDK can simulate, none of them documented as behaviour:**
  - `fold()` also doffs. `unfold()` alone then leaves every new session at `NO_ELIGIBLE_DEVICE`.
  - Thermal CRITICAL raises a session error but **keeps streaming**.
  - 5% battery raises nothing.
  - A denied camera permission appears as a **stream** error under a STARTED session, and nothing retries it.
  - "Firmware update required" lets the first session stream ~15 s before the device ends it.
  - `checkPermissionStatus` with the glasses off **fails** rather than returning Denied.
- **What MDK cannot tell you:**
  - Doff doesn't pause the stream.
  - The background raw-stream pause that Meta's own sample works around doesn't happen.
  - `capturePhoto()` returns a 480×640 HEIC (or a 1×1 placeholder with a file feed), so the gain from stills is unmeasurable.
  - Bluetooth bandwidth, heat and photo latency are all unmeasurable too.
- More, with sources: [RESEARCH.md §2, §5a](RESEARCH.md).

## The first hardware change: a work light on the frame, switched by the quality gate

**Evidence** ([`eval/torch_pairs.py`](../eval/torch_pairs.py)). On the **same 8 plates**, shot dark and then lit by a phone torch:
- The gate passed **22% → 79%** of frames.
- Frames readable to a person went **44% → 85%**.

Dark is the commonest reason a capture fails, so a light removes the "Too dark" prompt, the single most avoidable interruption a tech in an attic hears. The app already measures darkness per frame (mean luma, dark fraction), so it can switch the light on by itself.

**What it won't fix, also measured:** on those plates, Opus read **42% vs 40%** of printed fields. It copes with dim plates; its misses are small text and motion. So the light makes the capture loop faster, not the reading better.

**Second:** a single-zone time-of-flight rangefinder aligned with the camera. Range plus the pinhole model gives the scale our 255% size error lacks. The tech still has to say what extent to measure.

**What I'd ask Meta:**
- Publish the frame layout and what MDK fakes.
- Provide a speech path that doesn't contend with the camera.
- Give stills a full-resolution option on the mock, so text accuracy can be tested before buying glasses.
