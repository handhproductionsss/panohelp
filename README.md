# PanoGuide — Guided 360° Scene Capture & On-Device Stitcher (Checkpoint Specification)

> **Purpose of this document**: This `README.md` is a complete architectural and implementation checkpoint for **PanoGuide**. Providing this document alone to an AI coding assistant or developer is intended to contain every product rule, mathematical convention, database schema, pipeline specification, UI/UX constraint, CI fix, and verification command needed to reproduce or extend the project without further manual instruction.

---

## 1. Product Vision, Constraints & Non-Goals

PanoGuide is an **offline, open-source Android application** (Kotlin + Jetpack Compose, Material 3, locked to **portrait orientation**) that guides a user standing in the center of a room through capturing a complete, evenly overlapping, consistently exposed set of photos, records exact camera poses, stitches an interactive 360°×180° equirectangular panorama directly on-device, and optionally exports raw frames + poses as a self-contained ZIP.

### Hard Constraints
1. **100% Offline**: No accounts, cloud sync, analytics, ads, telemetry, or internet permissions (`INTERNET` is not requested in `AndroidManifest.xml`).
2. **Portrait-Only Capture**: `MainActivity` is locked via `android:screenOrientation="portrait"` in `AndroidManifest.xml`. Landscape capture is intentionally unsupported.
3. **Single Fixed Back Lens Per Session (Widest Back Lens)**: Automatically selects the widest usable back-facing lens (physical ultrawide or logical multi-camera `minZoomRatio < 1.0x`) once when the session starts and uses that same lens for every shot in the session.
4. **No Numeric Capture Progress**: Never display numeric capture counts (e.g., `"12/25"`) or coverage percentages (`"48%"`) on the capture HUD or session cards. Coverage is communicated purely visually via the live 3D sphere overlay and compact polar radar.
5. **No Floor / Nadir Target**: Intentionally omits a straight-down (`-90°` pitch) target so the photographer's feet stay out of the panorama. Ceiling (`+90°` pitch) is included cleanly using singularity-free quaternions.
6. **Interruption-Safe Persistence**: Every photo JPEG, thumbnail, and pose metadata record is flushed immediately to disk + Room SQLite on capture so incoming calls, app switches, or process death never lose progress.
7. **Plain-Language Degraded Warnings**: If wide lens, AE/AWB lock, or gyroscope fusion is unavailable on the device, continue operating cleanly and display a short plain-language HUD banner.

---

## 2. Build Environment, Dependencies & CI Setup

### Project Identifiers & SDK
- **Application ID**: `com.aistudio.panoguide.vqmwrt` (Kotlin namespace remains `com.example`)
- **Min SDK**: `24` (Android 7.0)
- **Target / Compile SDK**: `36` (`compileSdk { version = release(36) { minorApiLevel = 1 } }`)
- **JDK Required for Build & Robolectric SDK 36**: **Java 21** (Robolectric `36` fails with `UnsupportedOperationException` on JDK 17)
- **Signing**: Preserves `signingConfigs` (`release` + `debugConfig` using `${rootDir}/debug.keystore` with storepass/keypass `"android"`, alias `"androiddebugkey"`).

### Core Libraries (`gradle/libs.versions.toml` / `app/build.gradle.kts`)
- **UI**: Jetpack Compose BOM + Material 3 + Material Icons Extended + Navigation Compose (type-safe `@Serializable` routes via `kotlinx.serialization`)
- **Camera**: CameraX `1.5.0` (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) with `Camera2Interop` for AE/AWB lock and optical FOV inspection
- **EXIF**: `androidx.exifinterface:exifinterface:1.4.0`
- **Persistence**: Room (`2.7.2` with KSP annotation processing) + DataStore Preferences (`1.1.4`)
- **Serialization**: `org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0`
- **Fonts**: Bundled local `.ttf` resources in `app/src/main/res/font/`: `space_grotesk.ttf`, `plus_jakarta_sans.ttf`, `jetbrains_mono.ttf`
- **Tests**: JUnit 4 + Robolectric `4.14.1` (`@Config(sdk = [36])`)

### Permissions (`app/src/main/AndroidManifest.xml`)
- `<uses-feature android:name="android.hardware.camera" android:required="false" />` (keeps app installable / testable on camera-less devices and emulators in Simulated Mode)
- `<uses-permission android:name="android.permission.CAMERA" />` (runtime permission gated by a one-line explanation screen)
- `<uses-permission android:name="android.permission.VIBRATE" />` (normal install-time permission for capture haptic feedback)
- `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />` (only needed on Android 7–9 for public Pictures/Downloads fallback; Android 10+ uses zero-permission `MediaStore`)
- `FileProvider` (`${applicationId}.fileprovider`) configured with `res/xml/file_paths.xml` exposing `cache-path` and `files-path` for sharing ZIPs and stitched JPEGs.

### GitHub Actions Workflow (`.github/workflows/android-build.yml`)
Important CI requirements if replicating the pipeline:
1. Must set `actions/setup-java@v5` with `java-version: '21'`.
2. `debug.keystore` is gitignored, so CI must synthesize `${{ github.workspace }}/debug.keystore` before `:app:assembleDebug`:
   ```bash
   keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```
3. Set up Gradle `9.3.1` via `gradle/actions/setup-gradle@v4` and run `gradle :app:testDebugUnitTest` followed by `gradle :app:assembleDebug` (uses `gradle` CLI directly rather than `./gradlew`).

---

## 3. Mathematical Foundations & Coordinate System

All spatial math lives in `com.example.domain.math.SphereMath.kt`.

### World & Camera Reference Frames
- **World Frame (Right-Handed Cartesian)**:
  - `+X`: Right / East (at `yaw = 0°`)
  - `+Y`: Forward / Reference North (`yaw = 0°` azimuth reference)
  - `+Z`: Up / Zenith (opposite gravity)
  - Verify: `(+X) × (+Y) = (+Z)`.
  - Note: Under `TYPE_GAME_ROTATION_VECTOR`, `+Y` is the horizontal heading at sensor start rather than magnetic North.
- **Camera Portrait Local Frame (before rotation)**:
  - `+X_cam`: Right edge of the portrait photo
  - `+Y_cam`: Top edge of the portrait photo
  - `-Z_cam`: Optical viewing axis (direction back camera points)
  - `+Z_cam`: Out of the screen toward the photographer

### Singularity-Free Orientation (`Quaternion` & `CameraPose`)
- `Quaternion(w, x, y, z)` rotates vectors from Camera Local Frame to World Frame:
  $$v_{\text{world}} = q \otimes (0, v_{\text{cam}}) \otimes q^{-1}$$
  Because orientation is stored and interpolated as unit quaternions + basis vectors (`forward = q.rotate((0,0,-1))`, `up = q.rotate((0,1,0))`, `right = q.rotate((1,0,0))`), there is **zero gimbal lock at zenith (`+90°` pitch)**.
- Convenience Euler extraction (`CameraPose.fromQuaternion`):
  - `pitchDeg` $\in [-90, 90]$: $\arcsin(\text{forward}_z)$
  - `yawDeg` $\in [0, 360)$: clockwise from `+Y` (`atan2(forward.x, forward.y)` away from poles; near zenith uses `atan2(ref.x, ref.y)` with `ref = -up` so panning yaw at zenith stays smooth)
  - `rollDeg` $\in (-180, 180]$: clockwise tilt around optical axis relative to world horizon; set to `0f` when `|pitchDeg| > 80°` so ceiling capture never complains about horizon roll.
- `SphereMath.projectDirectionToScreen`: pinhole projection (`halfTanH = tan(hfov/2)`, `halfTanV = tan(vfov/2)`) maps any world unit vector into portrait viewport coordinates `(x, y)` plus `isInFront` / `isInsideViewport` for off-screen directional guidance chevrons.

---

## 4. Architecture & Module Map

```
app/src/main/java/com/example/
├── MainActivity.kt                        # Portrait-locked ComponentActivity + Navigation Compose NavHost
├── domain/
│   ├── math/SphereMath.kt                 # Vec3, Quaternion (slerp, basis), CameraPose, pinhole projection
│   └── capture/
│       ├── TargetPlanner.kt               # FOV-driven multi-ring target layout (~27.5% overlap, no nadir)
│       └── AimingGuidanceEngine.kt        # Pure-Kotlin state machine: snap target, retake, tilt, speed lock, hold timer
├── data/
│   ├── local/
│   │   ├── PanoEntities.kt                # Room SceneEntity & TargetEntity (DB v2)
│   │   ├── PanoDao.kt                     # Flow queries & atomic transactional inserts/replacements
│   │   ├── PanoDatabase.kt                # Room DB v2 + MIGRATION_1_2
│   │   └── AppPreferences.kt              # DataStore: sound_enabled, simulated_mode
│   ├── camera/
│   │   ├── WideLensSelector.kt            # Pure-Kotlin selector preferring standalone ultrawide or minZoomRatio < 1.0
│   │   ├── CameraController.kt            # CameraX Preview + ImageCapture (4:3), Camera2Interop AE/AWB lock, pose timestamp lookup
│   │   ├── ExifPoseWriter.kt              # Writes FocalLength, Orientation, DateTimeOriginal, SubSecTimeOriginal, Pose Comment JSON
│   │   └── SimulatedCameraEngine.kt       # Raycasted procedural 3D test room (walls, windows, parquet floor, skylight)
│   ├── sensor/
│   │   ├── PoseHistoryBuffer.kt           # Thread-safe timestamped ring buffer with quaternion slerp interpolation
│   │   └── OrientationSensorSource.kt     # Hardware sensor fusion (GAME_ROTATION_VECTOR priority) + SimulatedOrientationSensorSource
│   ├── stitch/
│   │   ├── EquirectangularMath.kt         # Small-angle (±2°) overlap SAD refinement, overlap exposure gain balance, feathered blending
│   │   ├── GPanoXmpWriter.kt              # APP1 Google Photo Sphere XMP injector
│   │   └── OnDevicePanoramaStitcher.kt    # Multi-core CPU strip stitcher saving local JPEG + Pictures/PanoGuide MediaStore
│   ├── export/
│   │   └── PackageExporter.kt             # ZIP packager -> cache + Downloads/PanoGuide (photos/*.jpg, poses.json, README.txt)
│   └── repository/
│       └── SceneRepository.kt             # Scene lifecycle, replanning before 1st capture, internal photo/thumb file paths
└── ui/
    ├── theme/{Color.kt,Type.kt,Shape.kt,Theme.kt}
    ├── navigation/PanoDestinations.kt     # SessionsRoute, CaptureRoute(sceneId), PanoramaViewerRoute(sceneId)
    ├── sessions/{SessionsViewModel.kt,SessionsScreen.kt}
    ├── capture/{CaptureViewModel.kt,CaptureScreen.kt,SphereOverlayCanvas.kt,MiniSphereRadar.kt}
    └── viewer/PanoramaViewerScreen.kt     # Auto-stitching, interactive 360° perspective pan viewport, Flat 2:1 toggle, Share JPEG
```

---

## 5. Key Algorithms & Engineering Details

### 5.1 Widest-Back-Lens Selection (`WideLensSelector` + `CameraController`)
- Inspects every `CameraInfo` via `Camera2CameraInfo` matching `LENS_FACING_BACK`.
- Computes portrait optical FOV from `SENSOR_INFO_PHYSICAL_SIZE`, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, and `SENSOR_ORIENTATION` (handling 90°/270° portrait sensor swap).
- Filters out tiny auxiliary macro/depth sensors (`maxMegapixels < 1.9 MP`).
- Supports **both** OEM multi-camera conventions:
  1. **Standalone physical ultrawide `CameraInfo`** (`baseHfovDeg >= 70°`): binds directly via custom `CameraSelector` filter.
  2. **Logical multi-camera wrapping ultrawide in `ZoomState.minZoomRatio < 0.96f`**: binds logical camera and immediately calls `camera.cameraControl.setZoomRatio(minZoomRatio)`, scaling `effectiveHfovDeg` / `effectiveVfovDeg` via `2 * atan(tan(fov/2) / zoom)`.
- Defaults in **Simulated Mode**: `hfovDeg = 74°`, `vfovDeg = 92°`, `focalLengthMm = 2.2f`, `equivalent35mm = 14`, `isUltrawide = true`.
- When an untaken real-camera scene opens and `CameraController` reports `hardwareInfo.isReady`, `SceneRepository.updateCameraSpecsAndReplanIfUntouched(...)` recomputes all sphere targets from the actual measured wide FOV before the first photo is taken.

### 5.2 FOV-Driven Coverage Planning (`TargetPlanner`)
- `DEFAULT_TARGET_OVERLAP = 0.275f` (midpoint of 25–30%).
- `verticalStepDeg = (vfovDeg * (1 - overlap)).coerceIn(25f, 65f)`.
- Rings generated in order:
  1. **Eye Level** (`pitch = 0°`): $N = \max(4, \lceil 360 / (\text{hfov} \cdot (1 - \text{overlap})) \rceil)$ targets around `0..360°`.
  2. **Upper Ring** (`pitch = min(verticalStepDeg, 62°)`): $N = \max(4, \lceil 360 \cdot \cos(\text{pitch}) / (\text{hfov} \cdot (1 - \text{overlap})) \rceil)$, offset by half an azimuth step for checkerboard seam overlap.
  3. **Ceiling** (`pitch = +90°`): single target (`ceiling_0`, if `includeCeiling = true`).
  4. **Lower Ring** (`pitch = max(-verticalStepDeg, -62°)`): staggered by half an azimuth step; nadir floor directly below stays uncaptured.

### 5.3 Sensor Fusion & Exposure-Time Pose Matching
- **Sensor Priority (`AndroidOrientationSensorSource`)**:
  1. `Sensor.TYPE_GAME_ROTATION_VECTOR` (gyro + accelerometer, **no magnetometer** — immune to indoor steel beams, wiring, and speaker distortion over a 1–2 minute capture).
  2. Fallback `Sensor.TYPE_ROTATION_VECTOR` (shows compass warning banner).
  3. Fallback `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` (shows compass warning banner).
  - Angular speed (`deg/s`) prefers `Sensor.TYPE_GYROSCOPE` magnitude smoothed (`0.45 * prev + 0.55 * curr`); falls back to differentiating successive orientation quaternions.
- **Exposure Pose Slerp (`PoseHistoryBuffer`)**: Stores ~200 recent timestamped `CameraPose` samples (`SystemClock.elapsedRealtimeNanos()` timebase). During `ImageCapture.OnImageCapturedCallback.onCaptureSuccess`, checks if `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME` and matches `image.imageInfo.timestamp` (or `onCaptureStarted` realtime timestamp) via quaternion **slerp**.

### 5.4 Aiming Guidance State Machine (`AimingGuidanceEngine`)
Evaluated at ~60 Hz (`delay(16L)`):
- `aimToleranceDeg = 6.0f`
- `tiltWarningThresholdDeg = 8.0f`, `maxAutoRollDeg = 10.0f` (ignored when `|pitch| > 75°`)
- `autoHoldMaxSpeedDegPerSec = 4.0f` (must stay steady under this speed to accumulate hold timer)
- `fastMotionLockSpeedDegPerSec = 10.0f` (locks both auto and manual capture and displays Coral `"Moving too fast — hold steady"` pill)
- `autoHoldDurationMs = 650L` (resets on fast motion/off-target, decays gently `dt * 2` on minor jitter)
- **Tap-to-Retake**: Clicking a thumbnail or target marker sets `lockedRetakeTargetId`, allowing auto- or manual re-capture of an already captured target.

### 5.5 Exposure & White Balance Consistency (`CameraController.lockExposureAndWhiteBalance`)
- Unlocks AE/AWB before shot #1 so the camera meters the scene naturally.
- Immediately after shot #1 succeeds, engages `CaptureRequest.CONTROL_AE_LOCK = true` and `CONTROL_AWB_LOCK = true` via `Camera2CameraControl` (if supported by `CONTROL_AE_LOCK_AVAILABLE` / `CONTROL_AWB_LOCK_AVAILABLE`), keeping brightness and color temperature locked across the remaining shots.
- If unsupported, displays `ae_lock_unsupported` / `awb_lock_unsupported` warning pills.

### 5.6 On-Device Panorama Stitcher & 360° Viewer (`data/stitch/` & `PanoramaViewerScreen.kt`)
Implemented as a **multi-core CPU strip-based spherical stitcher** (`Dispatchers.Default`, 2–6 bounded async workers processing 96-row horizontal strips) rather than OpenGL shaders to keep peak heap bounded cleanly on mid-range phones and avoid vendor GLSL texture limits:
1. **Decode & Orient**: Loads each captured JPEG subsampled cleanly based on `Runtime.maxMemory()` (`4096×2048` on >= 450 MB heap, `3072×1536` on >= 256 MB, `2400×1200` on smaller heaps), honoring EXIF rotation and using the recorded exposure-time unit `Quaternion` (with Euler fallback).
2. **Overlap Refinement & Exposure Compensation (`EquirectangularMath.refineOverlapsAndExposure`)**:
   - Builds a `240×120` reference spherical grid.
   - Computes per-frame overlap luminance ratios relative to the weighted mean across overlaps and solves clamped exposure gains `[0.78, 1.30]`.
   - Performs greedy `[-2°, -1°, 0°, +1°, +2°]` SAD alignment search in yaw & pitch (keeping adjustments that improve overlap SAD by at least 6%) to absorb residual gyro drift or parallax.
3. **Strip Rendering (`EquirectangularMath.renderStrip`)**:
   - Bilinear sampling from source frames mapped by precomputed 3×3 world-to-camera matrices (`qYaw * quaternion * qPitch`).
   - Blending weights: `((1 - |nx|) * (1 - |ny|))² * max(0.15, 1.15 - 0.35 * (nx² + ny²))` for smooth feathering + center-lens crispness.
   - Fills uncaptured nadir region cleanly with `#141922`.
4. **Metadata & Saving**:
   - Saves internal `filesDir/scenes/<sceneId>/stitched_panorama.jpg` (JPEG quality 92).
   - Writes standard EXIF tags (`Orientation`, dimensions, `Make`, `Model`, `DateTimeOriginal`).
   - Injects a JPEG `APP1` **Google Photo Sphere (`GPano`) XMP** block (`GPano:UsePanoramaViewer="True"`, `GPano:ProjectionType="equirectangular"`, full/cropped dimensions) via `GPanoXmpWriter.injectIntoJpeg`.
   - Publishes copy to public `Pictures/PanoGuide/PanoGuide_<slug>_360_<timestamp>.jpg` via `MediaStore.Images` (`IS_PENDING` safe write on Android 10+).
5. **Interactive 360° Viewer (`PanoramaViewerScreen.kt`)**:
   - Auto-stitches on open if no stitched file exists yet, displaying live stage progress.
   - Default mode renders a draggable perspective camera viewport (`renderPerspectiveViewport`, `72°` HFOV) directly from the equirectangular buffer; top-right toggle switches to **Flat 2:1** full preview; bottom bar offers **Re-stitch** and **Share JPEG**.

### 5.7 Raw Export Package Format (`PackageExporter`)
Saves `PanoGuide_<slug>_<timestamp>.zip` to `Downloads/PanoGuide/` and `cacheDir` (for Share Sheet):
- `photos/001_<ring>_<yaw>.jpg ...` (all captured photos in capture order, EXIF intact)
- `poses.json` (`PosesDocument`: `formatVersion = "1.0"`, `scene.settings` including `isUltrawide`, `hfovDeg`, `vfovDeg`, `aeLocked`, `awbLocked`, `sensorSource`, `coordinateConvention`, and `photos[]` with `quaternion {w,x,y,z}`, `forwardVector`, `upVector`, `eulerDeg {yaw,pitch,roll}`, `capturedAtIso`, `camera`)
- `README.txt` (human & script-readable explanation of coordinate convention)

---

## 6. Database Schema (Room v2) & Simulated Mode

### Room Schema (`PanoDatabase`, `version = 2`, `MIGRATION_1_2`)
- **`scenes` (`SceneEntity`)**: `id` (PK), `name`, `createdAtMs`, `updatedAtMs`, `status` (`IN_PROGRESS` | `EXPORTED`), `hfovDeg`, `vfovDeg`, `overlapTarget`, `focalLengthMm`, `lensId`, `aeLocked`, `awbLocked`, `aeLockSupported`, `awbLockSupported`, `sensorSource`, `nextCaptureOrder`, `lastExportUri`, `lastExportDisplayName`, `isSimulated`, `isUltrawide`, `stitchedImagePath`, `stitchedSavedName`.
- **`targets` (`TargetEntity`)**: composite PK `(sceneId, id)`, FK `sceneId -> scenes.id` (`CASCADE`), `orderIndex`, `ringType`, `targetYawDeg`, `targetPitchDeg`, `targetDir{X,Y,Z}`, `captured`, `photoFilePath`, `thumbFilePath`, `captureOrder`, `capturedAtMs`, `actualDir{X,Y,Z}`, `actualUp{X,Y,Z}`, `quat{W,X,Y,Z}`, `actual{Yaw,Pitch,Roll}Deg`, `imageWidth`, `imageHeight`.

### Built-In Simulated Mode (Hardware-Free Verification)
Toggleable via Switch on `SessionsScreen` (or `"Use Simulated Mode"` button on Camera Permission gate):
- Replaces camera preview with `SimulatedCameraEngine.renderRoomToCanvas` (perspective-projected procedural 3D architectural room with windows, doors, artwork, trim, parquet floor, beams, and skylight).
- Dragging pans yaw/pitch across the sphere; HUD provides `Aim Nearest`, `Tilt 15°`, and `Shake` buttons to test auto-capture, horizon tilt warnings, motion lock, tap-to-retake, on-device stitching, 360° viewing, and ZIP export without a physical phone.

---

## 7. UI Design System & Navigation

### Palette & Typography (`ui/theme/`)
- Precision dark capture HUD (`Obsidian = #0B0E14`, `CharcoalSurface = #131822`, `SignalCyan = #38BDF8`, `MintEmerald = #34D399`, `AmberCaution = #FBBF24`, `CoralAlert = #F87171`, `OffWhiteText = #F1F5F9`) paired with adaptive light/dark surface palette on Sessions Home.
- Bundled typography: `Space Grotesk` (display/headers), `Plus Jakarta Sans` (body/labels), `JetBrains Mono` (HUD tags/code badges).

### Type-Safe Navigation (`PanoDestinations.kt` + `MainActivity.kt`)
- `SessionsRoute` → `SessionsScreen` (`new_scene_fab`, per-card `resume_scene_<id>`, `stitch_scene_<id>`, `share_scene_<id>`, `delete_scene_<id>`, `simulated_mode_switch`)
- `CaptureRoute(sceneId)` → `CaptureScreen` (`capture_back_button`, `sound_toggle_button`, `finish_scene_button`, `manual_shutter_button`, `confirm_stitch_button`, `confirm_export_button`, plus sim buttons `sim_aim_nearest_button`, `sim_tilt_chip`, `sim_shake_chip`)
- `PanoramaViewerRoute(sceneId)` → `PanoramaViewerScreen` (`viewer_back_button`, `viewer_mode_toggle`, `viewer_restitch_button`, `viewer_share_button`)

---

## 8. Build & Verification Commands

```bash
# Run all pure-JVM unit tests (SphereMath, TargetPlanner, AimingGuidance, WideLensSelector,
# EquirectangularMath, GPanoXmpWriter) and Robolectric integration tests (ZIP export + OnDevicePanoramaStitcher)
gradle :app:testDebugUnitTest

# Assemble installable debug APK -> app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleDebug
```
