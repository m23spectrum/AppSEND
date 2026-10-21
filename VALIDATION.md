# Validation & Quality Assurance — AppSEND 1.6.1

Summary of automated test suites, build targets, and protocol qualification.

| Test Suite / Target | Details | Result |
|---|---|---|
| Desktop Protocol Tests (`npm test`) | 16 unit tests covering framing, crypto vectors, UDP discovery, and engine state transitions | **Passed** (16/16) |
| Android Unit Tests (`gradlew testDebugUnitTest`) | 43 unit tests covering gestures (grab gate, posture, approach), crypto vectors, and real Ktor engine interop | **Passed** (43/43) |
| Android Build (`gradlew assembleDebug`) | Complete compilation of Kotlin / Jetpack Compose application | **Passed** |
| Android Lint (`gradlew lintDebug`) | Android static analysis | **Passed** (0 errors) |
| Windows Installer (`npm run dist`) | Electron 64-bit NSIS packaging | **Passed** |
| Cross-Platform Engine Interop | Headless Node.js peer interacting with JVM Android engine over real WebSockets and encrypted HTTP streaming | **Passed** |

## Test Coverage Details

### Gesture Engine (`GrabGateTest`, `ApproachGateTest`)
- Hand detection & fist hold debounce (70 ms threshold at confidence ≥ 0.60).
- Open palm trigger debounce (45 ms threshold at confidence ≥ 0.60).
- Rejection of accidental hand loss in center frame without an open-palm trigger.
- Peripheral frame exits (top, bottom, left, right edges) supported as an optional alternative.
- One-shot release guarantee preventing duplicate transfers on a single gesture.
- Manual cancel and hold reset behavior.

### Cryptography & Networking (`CryptoTest`, `EngineTest`)
- Interoperable X25519 ECDH key exchange between Node `crypto` and Android Java crypto.
- HKDF-SHA256 session key derivation with mutual salt and info parameters.
- AES-256-GCM chunked stream encryption with monotonic sequence counters.
- UDP discovery broadcast packet format and peer expiration timers.
- Local loopback and LAN transfer validation.

## Release Notes
- The debug APK and NSIS Windows installer are built for development and local testing.
- For production distribution, standard developer certificates (Play Console keystore and Windows Authenticode) should be applied.
- See `SECURITY.md` for threat model and encryption details.
