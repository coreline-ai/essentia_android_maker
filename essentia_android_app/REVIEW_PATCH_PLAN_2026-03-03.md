## Patch Plan (2026-03-03)

### Goal
- Reduce regression risk for the next review by hardening runtime stability and making failure states observable.

### Scope
- Android app runtime code under `app/src/main`.
- Android artifact sync/build helper scripts:
  - `scripts/sync_essentia_artifacts.sh`
  - `build_android.sh`

### Prioritized Risks and Actions
1. Resource leak on decoder/extractor failure path.
   - Wrap `MediaCodec`/`MediaExtractor` lifecycle with structured `try/finally` cleanup.
2. High memory retention after analysis.
   - Avoid storing full PCM buffers in UI state.
   - Keep only lightweight decoded metadata in state.
3. Hidden partial analysis failures.
   - Surface non-fatal analysis errors in UI as warnings.
4. Sync script portability risk.
   - Replace machine-specific default paths with repo-local defaults and deterministic fallback.
5. Build option drift between app JNI (`c++17`) and native build helper default.
   - Align helper default standard with app JNI standard.

### Out of Scope
- Large architectural changes (streaming JNI contract redesign).
- Essentia algorithm-level modifications.
- New test framework adoption.

### Validation Checklist
- `:app:assembleDebug` succeeds.
- File selection and analysis flow still works.
- On success, UI does not retain large PCM payload in state.
- Both fatal and non-fatal analysis errors are visible in result tab.
- Sync script works with repo-local defaults when package artifacts exist.

