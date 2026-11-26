# Maestro UI flows

UI tests that drive the debug build on a real phone through its on-screen buttons, using
[Maestro](https://github.com/mobile-dev-inc/maestro) (Apache-2.0). Voice commands can't be injected from
Maestro; those are covered by the adb driver (`DebugCommandReceiver`), `tools/phone_smoke.ps1` and the
instrumented tests.

| Flow | What it checks |
|---|---|
| `01_settings_toggles.yaml` | Every debug setting flips and flips back |
| `02_job_buttons.yaml` | Start, pause, resume and end a job with the buttons, then start a second job |
| `03_rapid_taps.yaml` | Double taps and Pause/Resume tapped before the app catches up: one job, a consistent final state |
| `04_glasses_events_mid_job.yaml` | Simulated glasses switched off, taken off, folded, overheated and at 5% battery mid-walk; the walk recovers each time |
| `05_background_mid_job.yaml` | Leaving the app mid-walk (home button) and coming back; frames flow again |

Run from the repo root, with the debug build installed and the phone unlocked:

```
powershell -ExecutionPolicy Bypass -File tools\maestro_run.ps1                 # all flows
powershell -ExecutionPolicy Bypass -File tools\maestro_run.ps1 -Flow 03_rapid_taps.yaml
```

The runner turns Maestro's analytics off, and mutes the phone mic whenever a job starts so room sound
can't trigger commands. Maestro's `launchApp` also tries to grant every runtime permission; ColorOS
refuses the shell's grant (logged, harmless, because the app's permissions are already granted). It selects the synthetic test feed, so nothing in the room is filmed, and puts the
phone camera back afterwards. No flow uses `clearState`, which would delete real jobs.
