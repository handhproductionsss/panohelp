# PanoGuide — Guided 360° Photo Capture (Android)

An offline, open-source Android app that guides a photographer through capturing a complete, evenly overlapping, consistently exposed set of portrait photos for 360° panorama stitching on a computer.

## Features
- **FOV-driven sphere target planning**: Computes ring elevation angles and azimuth target counts from the back camera's physical sensor size and focal length (~27.5% overlap, staggered upper/lower rings, optional ceiling shot, intentionally no floor/nadir target).
- **Live 3D sphere overlay**: Projects captured thumbnails and empty target markers into 3D space over the live portrait camera preview, with a visual-only 360° mini-radar and directional guidance chevron (no numeric progress).
- **Auto-capture & manual shutter**: Triggers automatically after holding steady on target (with visual flash, haptic pulse, and optional shutter chime), or via manual tap. Blocks capture during fast rotation to prevent motion blur, and warns clearly on horizon tilt.
- **Exposure & white balance lock**: Locks AE/AWB via Camera2Interop after the first shot so seams stay consistent; warns plainly when a phone does not support AE/AWB locking.
- **Interruption-safe persistence**: Saves every photo immediately to internal storage and records target state in Room so phone calls or app switches never lose progress.
- **Self-contained ZIP export**: Writes `photos/001_*.jpg` (with standard EXIF tags), `poses.json`, and `README.txt` to `Downloads/PanoGuide/` and opens the Android system Share sheet.
- **Built-in Simulated Mode**: Interactive 3D virtual room + simulated orientation controls (`Aim Nearest`, `Tilt 15°`, `Shake`) for testing the entire capture, retake, and export flow on an emulator or computer without physical hardware.

## Coordinate Convention & Sensor Trade-off
- **Sensor Choice**: Prefers `Sensor.TYPE_GAME_ROTATION_VECTOR` (gyro + accelerometer fusion without magnetometer) so indoor steel frames, wiring, and speakers do not distort yaw spacing. Falls back to `TYPE_ROTATION_VECTOR` or Accelerometer + Magnetometer if no gyroscope is present, showing a clear warning banner.
- **Exposure-Time Pose Lookup**: Matches each captured photo's sensor timestamp (via `PoseHistoryBuffer` slerp interpolation) to the actual moment of exposure rather than button-tap time.
- **Coordinate System**: Right-handed world frame (`+X` Right/East, `+Y` Forward/North, `+Z` Up/Zenith). Poses are exported as singularity-free unit quaternions `(w, x, y, z)`, `forwardVector`, `upVector`, and convenience Euler angles (`yaw`, `pitch`, `roll` in degrees). Full details are included in `README.txt` inside every exported ZIP.

## Build & Run Tests
```bash
# Run all unit & Robolectric tests (target placement, aiming logic, simulated export)
gradle :app:testDebugUnitTest

# Build an installable debug APK
gradle :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```
