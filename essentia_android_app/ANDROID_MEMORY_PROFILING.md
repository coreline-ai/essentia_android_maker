## Android Memory Profiling Guide

### Purpose
- Reproduce and validate memory behavior for long audio analysis (10-15 minutes).
- Catch regressions after decoder/JNI/state-flow changes.

### Preconditions
- Build variant: `debug`
- Device ABI: `arm64-v8a`
- Test media:
  - Case A: ~3 min (baseline)
  - Case B: ~10 min
  - Case C: ~15 min (max supported)

### 1) Build and install
```powershell
cd d:\project_new\essentia_android_maker\essentia_android_app
.\gradlew.bat :app:assembleDebug :app:installDebug
```

### 2) Start clean log/memory sampling
```powershell
adb logcat -c
adb shell am force-stop com.iriver.essentiaanalyzer
adb shell am start -n com.iriver.essentiaanalyzer/.MainActivity
```

### 3) Runtime memory snapshots (`dumpsys meminfo`)
Take snapshots at these checkpoints:
- T0: app launched, before file selection
- T1: file selected, before analysis start
- T2: during decoding
- T3: during analysis
- T4: after analysis complete and idle (wait 10-20s)

Command:
```powershell
adb shell dumpsys meminfo com.iriver.essentiaanalyzer
```

Record at minimum:
- `TOTAL PSS`
- `Native Heap`
- `Dalvik Heap`
- `Graphics`

### 4) Android Studio Memory Profiler
- Open `Profiler` -> select process `com.iriver.essentiaanalyzer`.
- Capture:
  - Heap timeline during T1-T4.
  - Allocation recording around decode start and analysis start.

Focus checks:
- Peak memory occurs during decode/analyze (expected).
- After T4, memory should drop relative to peak and stabilize.
- No monotonic increase across repeated runs of the same file.

### 5) Repeatability run
Run Case B (10 min) at least 3 times in one app session:
- If each run increases post-analysis baseline significantly, treat as leak suspect.

### 6) Pass/Fail criteria
- Pass:
  - No crash (`OOM`, native abort, watchdog reset).
  - Post-analysis memory baseline is stable across repeated runs.
- Fail:
  - `OutOfMemoryError`, repeated native failures due to resource exhaustion,
  - or baseline memory keeps climbing run-by-run.

### 7) Report template
Use this fixed format for review consistency:

```text
Device:
Build:
Media case:
T0/T1/T2/T3/T4 TOTAL PSS:
Native Heap trend:
Dalvik Heap trend:
Crash or warnings:
Conclusion (Pass/Fail):
```

