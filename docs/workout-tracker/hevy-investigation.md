# Hevy workout logging investigation

Investigated 29 October 2026 to inform an independent Pulse implementation. Companion: [implementation plan](README.md).

## Evidence boundary

**Static** means observed declarations or decoded code in the acquired Android artifact. **Documented** means Hevy's public first-party product/help/API material. **Inferred** is an interpretation of those observations. **Proposed** decisions belong to Pulse's plan. None of these is an observed Hevy phone session.

REA 6.5.0 inspected the package and selected Android classes/methods, using the existing JADX headless provider 0.7.1. The connected MCP server lacked the Android provider environment, so Android analysis used the same installed REA CLI with process-scoped Java/JADX settings. No REA configuration or installation was changed. The archive inventory used the connected REA MCP server.

The application bundle is Hermes bytecode, rather than JavaScript source. Selected bytecode functions were inspected with the open-source [P1sec Hermes disassembler](https://github.com/P1sec/hermes-dec), revision `2e796663e1fedb93dca51060797ebf3327b3fedb` (tool version 0.1.8). This is supplemental static evidence, **not REA-produced Evidence** and not recovered original source. Function IDs/offsets below refer only to this exact bundle.

No Hevy APK was installed or executed. No login, paid API key, authenticated workout API request, user database or network interception was used. Public documentation/schema retrieval was read-only. The base APK was analyzed; native library splits were not decompiled. Selected methods/functions establish bounded paths, not complete application coverage.

### Acquired artifact

The [public APKPure download endpoint](https://d.apkpure.net/b/APK/com.hevy?version=latest) served an XAPK for package `com.hevy`, matching the package in the [official Play listing](https://play.google.com/store/apps/details?id=com.hevy). The endpoint is mutable; the hashes below identify the artifact analyzed. This is a third-party acquired artifact, not an independently downloaded Play installation. We do not assert that it is the globally newest release or that its publisher identity was independently pinned against Play.

| Item | Identity |
|---|---|
| Container | Hevy 3.2.4, version code 3403347; 114,178,724 bytes; XAPK with base APK, armeabi-v7a/English/mdpi splits, icon and manifest |
| Container SHA-256 | `3c7e32ff792607feeadb49dbec681bc997f6d2b2ad574a74de2be1117e611690` |
| Base APK SHA-256 | `a1bb701bef5a8a6bcba9dcacd9502ad8e57cc170abb5a68808693962a82d9e7c` |
| Package inventory | 19,093 classes; minimum SDK 24, target SDK 36 |
| Bundle | `assets/index.android.bundle`, 22,712,740 bytes, Hermes bytecode version 96 |
| Bundle SHA-256 | `dce084dea770f7cadc356f8e22c1f50692218a5f3e56f2ea16ce974aa3aad4b1` |
| APK signer SHA-256 | `add9aa0d804a1a14a04119d44fa7cb4cc229c86322991eda526f5455d11580ef` |

Android build-tools `apksigner verify --print-certs` accepted the base APK signature. Cryptographic verification establishes package integrity under that signer; it does not independently establish the publisher's identity. Raw APKs, disassemblies and complete local evidence stay outside this public repository. This document publishes factual summaries, hashes and reproducible evidence references, not proprietary source/assets or bundled configuration values.

## How logging works

### 1. Start from a plan or a blank session

**Documented:** Hevy supports starting empty, starting a routine, or copying a past workout. [Logging guide](https://help.hevyapp.com/hc/en-us/articles/35361530647959-How-to-Log-a-Workout-in-the-Hevy-App-Step-by-Step-Guide).

**Static:** `startWorkout` (function 36712, offset `0x01271c36`) checks active-workout/watch state before starting. `createEmptyWorkoutState` (36731) and routine/normalized-workout conversion functions are separate paths. `normalizedWorkoutToLogWorkoutViewData` (32944) maps exercises and carries routine/time/context fields into an editable view model. This supports a distinction between a routine, the current editor state and a saved workout; it does not establish every startup branch.

### 2. Enter actual sets alongside previous performance

**Documented:** the previous column can use any workout or the same routine. That setting does not update the target/input columns. Routine-value updates are controlled separately at save time; rep ranges have their own behavior. [Previous versus routine values](https://help.hevyapp.com/hc/en-us/articles/34105442929943-Previous-Workout-Values-Vs-Routine-Values-How-to-Adjust-in-Settings).

The [public API schema](https://api.hevyapp.com/docs/) describes ordered workouts → exercise occurrences → sets. Sets have a type and nullable load, reps, distance, duration and RPE. Routines carry target/rest/rep-range information; exercise templates identify measurement type, muscles and equipment. The schema is a public serialization contract, not proof of Hevy's internal database schema. In particular, public set order indices do not prove that Hevy lacks internal set IDs.

**Static:** function 36528 is installed as `WorkoutManager.onSetCompletedUpdate` by constructor 36484. Its decoded control flow accesses a selected exercise/set, handles a nonexistent set, updates completion-related fields including `completedAtUnix`, uses type-specific input/placeholder fields, and conditionally starts rest. This is stronger evidence than seeing feature names in strings. UI wrappers 47716 and 48532 delegate completion to this manager.

**Documented:** normal, warm-up, drop and failure sets are supported. Consecutive drop sets can suppress automatic rest. Hevy describes optional RPE and does not present RIR as an independent logging field in that guide. [Set types](https://help.hevyapp.com/hc/en-us/articles/34896293707927-Set-Types-in-Hevy-Explained-Drop-Sets-Warm-Up-Sets-and-More), [RPE and RIR](https://help.hevyapp.com/hc/en-us/articles/34490600233111-RPE-vs-RIR-What-They-Mean-and-How-to-Use-Them-in-Hevy).

Pulse should preserve an explicit zero-rep failed attempt, rather than adopting Hevy's documented workaround of using notes when zero reps are rejected. A failure-type set and an unsuccessful attempt are different data.

### 3. Complete a set and run a rest timer

**Static:** the completion handler considers superset progression, set limits, notification/haptic settings, per-exercise automatic rest and a following drop set. `AutoRestTimerViewModel` methods use start/current/end timestamps; start/add/subtract paths schedule notifications and persist timer state (functions 36815, 36817, 36818). This indicates a timestamp-based timer path, not merely a decrementing view counter.

REA-decompiled `TimerNotificationModule.setAlarm` creates a broadcast for `TimerExpiredReceiver`, carrying the message, sound, volume and scheduled timestamp. On supported Android versions it requests exact idle-capable alarms; Android 12+ scheduling is conditional on exact-alarm capability. This one method does not prove all fallback paths or reliable delivery under OEM battery controls. [Documented rest timer behavior](https://www.hevyapp.com/features/workout-rest-timer/).

### 4. Keep active state separate from completed history

**Static:** `CurrentWorkoutClass.save` (34080, offset `0x0123bab9`) serializes its argument to JSON and passes a `LocalStorage.set` promise for a dedicated current-workout key into a fire-and-forget helper. `clear` removes that key. A state reaction in `WorkoutManager` calls `normalizeAndPersistWorkout`; that closure (36490) normalizes current editor state and calls `persistWorkout` outside its saved-workout-editing branch.

This establishes a local active-workout persistence path. It does **not** establish write acknowledgment, debounce bounds, transaction durability, recovery after disk failure, or exactly what survives process termination. Pulse needs its own tested durability contract rather than assuming Hevy guarantees those properties.

### 5. Review, finish, and optionally update the routine

**Static:** the finish view model installs function 48965 as `onSavePress`. Its control flow compares the workout with its routine, regenerates time events from provisional start/duration, can open a routine-update choice, and calls the logger's throttled save path when applicable. This corroborates a review/save boundary and a routine-update decision. [Routine update versus keeping the original](https://help.hevyapp.com/hc/en-us/articles/38387296276375-Update-Routine-vs-Keep-Original-Routine), [duration and pause](https://help.hevyapp.com/hc/en-us/articles/34513981310615-How-to-I-adjust-duration-and-pause-a-workout).

`getSaveableNormalizedWorkout` (31657) maps exercise sets through type-dependent filtering and removes empty exercise occurrences via functions 31658–31660. The selected predicate considers supplied metric fields; this observation is **not** a claim that this helper alone enforces all completion/validation rules on the full save path.

### 6. Persist history and handle unsaved uploads

**Static, REA:** `WorkoutStorageModule.getWorkoutStorage` constructs its SQLite implementation. That implementation opens a database in app-private storage and creates a workouts table with an ID primary key, end time and JSON payload. `storeWorkouts` parses an array and inserts/replaces each workout using its ID. This is JSON-per-workout local storage, not evidence of a normalized relational exercise/set database. The selected method has no explicit transaction wrapping the whole array; that does not establish transaction behavior in every other path.

**Supplemental static:** the `UnsavedWorkouts` area contains a function (33271, offset `0x0122ba0f`) that calls `API.postWorkout`, checks saved-workout state, handles response/error branches and writes the remaining queue through LocalStorage. This supports a pending-upload mechanism. End-to-end retry/backoff, ordering, server idempotency and offline recovery guarantees remain unverified.

Pulse has no server/account. Its save operation should commit directly to the user's training store and finish immediately offline; it does not need to recreate Hevy's network architecture.

## Features worth learning from, and choices Pulse must own

| Hevy reference | Pulse implication |
|---|---|
| Targets and previous actuals are separate | Preserve both in the model/UI; do not silently turn planned sets into performed sets. |
| Typed sets, supersets, automatic rest | Choose measurement kinds and grouping IDs before designing one universal set row. |
| Active state and finished history are separate | Build a durable draft state machine and a single completion transaction. |
| Local history plus a pending network queue | Reuse the separation of concerns, while Pulse remains local-only. |
| Routine update at finish | Keep saved workouts immutable with respect to future routine edits; show an explicit update choice. |
| Historical records and estimated metrics | Publish Pulse's formula, eligibility and bodyweight/assistance policy. Do not assume equal numbers across apps. |

Hevy's [estimated 1RM guide](https://help.hevyapp.com/hc/en-us/articles/36954464726167-Understanding-Your-Estimated-One-Rep-Max-1RM-in-Hevy) describes rep-percentage estimates; do not call it a generic Epley implementation. Its [bodyweight guide](https://help.hevyapp.com/hc/en-us/articles/44098622782487-Bodyweight-Exercises-in-Hevy-Bodyweight-vs-Assisted-vs-Weighted) and [muscle-set guide](https://help.hevyapp.com/hc/en-us/articles/44128140257431-How-Does-Sets-per-Muscle-Group-Work) show why exercise-class and attribution policies matter. Interaction between every global warm-up preference and every chart was not verified.

Hevy supports [data export](https://help.hevyapp.com/hc/en-us/articles/43708290987415-Exporting-Your-Data-from-Hevy). Its [Health Connect integration](https://help.hevyapp.com/hc/en-us/articles/34204824335255-How-to-Connect-Hevy-to-Google-Fit-Using-Health-Connect) writes completed workouts; Pulse's existing read-only Health Connect contract is deliberately preserved in this plan. Social/cloud/watch features are not required to provide a full local gym log.

## Evidence ledger

Full REA JSON records and selected Hermes disassemblies are retained privately in this task's `work/hevy/` directory. Evidence IDs below refer to those complete records; they are not public web links. Android method Evidence carries the exact base APK and provider identities. Supplemental Hermes references are bound to the bundle SHA-256 above.

| Question / observation | Evidence reference | Coverage |
|---|---|---|
| XAPK member identities | REA `ev_7eadaed2c8bfb9832b82960dca21d0d3a3b094812d057edae420d64fd17b8662`; inventory `ev_c163cea81ac76db92bf87f99a312e3e6fb6a538ec9feea6719c98a20da99f46f` | Archive inventory only; no execution. |
| Package/version/classes/manifest | REA `ev_daa067fce17a6405fe714433a20d0e0eec22922b077ade7bd2ab3fc1f7750687` | Base APK parsed by JADX. |
| Native storage selection | REA `ev_c1e0ceab48c348a00639700102f2f165df3381d03c71addf48b015e273528752` | One method. |
| History table and insert path | REA `ev_18f6a3593c534b3381b0eba75ab1e43cad4cca500256d40d5608ed2d67615f7b`, `ev_e11ca5e872300a23b2b2be263b0967b014b091900e59b9c855c6e417c8300c17` | SQLite open/store methods; no live database. |
| Native rest alarm | REA `ev_1b516d9f60a8795e253a98494093b9849fab8a02c99a9c29e63962290a68bc52` | One scheduling method; delivery unknown. |
| File-backed large JSON storage also exists | REA `ev_14e5bc00ebfaf12bb9cf4e3cfd7d23ab383fb05fb4d0db5a32b67d27f4c8c22d` | BigStorage store method; not all callers traced. |
| Set completion and rest decision | Hermes functions 36484 → 36528 (`0x0126d300`); UI 47716 → 48532 | Selected decoded instructions/control flow, not execution. |
| Current state persistence | Hermes 34080 (`0x0123bab9`), 36673/36674, 36490 (`0x0126b68b`) | Local write path observed statically; crash guarantees unknown. |
| Timer timestamp/persistence paths | Hermes 36815/36817/36818; native alarm Evidence above | Selected functions, no locked-screen test. |
| Save review / routine decision | Hermes 48952 → 48965 (`0x013ca0be`) | Selected finish handler, not full save graph. |
| Typed filtering before save | Hermes 31657 → 31658/31659/31660 | One normalization helper; complete validation unknown. |
| Pending upload handling | Hermes 33271 (`0x0122ba0f`) | Local queue/API call path; retries/server semantics unknown. |

### Investigation checklist

- **Answered:** exact analyzed artifact, package structure, documented entry/edit/finish workflow, exercise/set/routine serialization, native saved-workout storage path and rest-alarm path.
- **Partially answered:** current-draft persistence, completion dispatch, save normalization, routine choice and pending-upload processing; bounded code paths traced, not exhaustive implementation recovery.
- **Unresolved:** observed phone UX, process-death/offline/low-disk guarantees, OEM timer delivery, server behavior, complete proprietary metric rules, every native split and every integration.
- **Planning complete:** the accompanying Pulse design identifies the missing functionality, data ownership hazards, target scope, dependencies, milestones and acceptance checks. Implementation and owner phone validation remain future work.
