# Star Odyssey (Android)

Android native launcher, binary dump validator, RomFS mod manager, controller/gyro mapper, and interactive OpenGL ES 3.0 pipeline runtime inspired by *Power Moon Recompiled / Super Odyssey Recomp*.

> **Bring Your Own Dump (BYOD):** This repository does not include any copyrighted game assets or proprietary keys. Users must provide their own legally dumped game files.

## Features

- **Hardware & Graphics Telemetry:** Probes ARM64 ABI, Vulkan hardware level/version, EGL/GLES GPU renderer, display refresh rate (60/120 Hz), RAM, gyroscope, and connected gamepads.
- **Smart File Auto-Detection & Binary Parsers:** Import via Android Storage Access Framework (`OpenDocument` / `OpenDocumentTree`) with automatic routing for:
  - `prod.keys` (`header_key`, `master_key_*`, `key_area_key_application_*`, SHA-256 verification)
  - `.nsp` (`PFS0` container table & entry parser)
  - `.xci` (`HEAD` cartridge header & `HFS0` root offset)
  - `.nca` (`NCA2` / `NCA3` content check)
  - `main` (`NSO0` segments `.text`/`.rodata`/`.data`/`.bss` & Build ID)
  - Extracted `RomFS` / `ExeFS` directory trees
- **RomFS Mod Manager:** Install `.zip` or folder mods into app-private storage, filter by name, toggle enabled state, and reorder load priority.
- **Graphics, Audio & Controls Configurator:** Configure resolution scale (`0.5x`–`2.0x 4K`), FPS target (`30`/`60`/`120`), AMD FSR 1.0 sharpness, SMAA, aspect ratio (`16:9`, `16:10`, `21:9`), color-banding dithering, GLSL ES 3.0 shader cache pre-compilation, `recomp_config.json` preview/export, `AudioTrack` PCM synth SFX/BGM, physical gamepad monitor, gyroscope motion-cap, and haptic vibration.
- **Interactive 3D Runtime (`GLSurfaceView`):** Live FBO resolution scaling, post-processing shader pass, virtual Switch Pro Controller overlay, render mode switcher (`Shaded`, `Wireframe`, `Night Aura`), and PNG framebuffer capture.

## Building

Open in Android Studio or build with Gradle:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```
