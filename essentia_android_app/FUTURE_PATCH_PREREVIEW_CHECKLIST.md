## Future Patch Pre-Review Checklist

Use this checklist before implementing any new patch.

### 1) Risk classification
- Label each change as `P0` / `P1` / `P2`.
- Define user-visible failure mode for each risk.

### 2) Change boundary
- List touched files and expected side effects.
- Confirm whether JNI contract, decoder path, or state-flow is impacted.

### 3) Cancellation and race safety
- If background work exists, verify in-flight invalidation/cancel semantics.
- Confirm stale results cannot overwrite newer UI state.

### 4) Memory and duration safety
- For audio decode paths, verify upper bounds for:
  - max duration
  - in-memory sample count
  - retained UI state payload size

### 5) Error visibility consistency
- Ensure fatal/warning classification uses shared logic.
- Validate warning counts in status and result panel are consistent.

### 6) Portability and build reproducibility
- Reject machine-specific hardcoded paths unless explicitly required.
- Keep CLI/script defaults repo-local where possible.

### 7) Mandatory verification commands
```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

### 8) Review output template
- Risks addressed:
- Remaining known risks:
- Validation results:
- Follow-up items for next patch:

