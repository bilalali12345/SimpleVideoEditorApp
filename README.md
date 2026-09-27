# Simple Video Editor

A small, no-frills Android video editor built for one purpose: making it easy for a
non-technical user to trim, merge, and clean up home videos — no timelines, no
complicated UI, just big buttons and plain language.

Built with Jetpack Compose, Media3 (ExoPlayer + Transformer), and a native RNNoise
integration for background noise removal.

## Features

- **Trim a video** — cut off the beginning, cut off the ending, or remove a chunk from
  the middle (the two remaining pieces are stitched back together automatically).
- **Merge two videos** — join two clips into one, back to back.
- **Remove background noise** — runs the video's audio through
  [RNNoise](https://github.com/xiph/rnnoise) (Xiph.Org's neural noise-suppression
  library) to strip out steady background noise like fans, wind, or hum, while
  leaving the video itself untouched.
- **Play videos in-app** before and after editing, via ExoPlayer.
- **Originals are never modified.** Every operation always writes a brand-new file;
  nothing is ever overwritten or deleted.
- Finished videos are saved through `MediaStore` into the phone's public **Movies**
  folder (under a `Simple Video Editor` subfolder), so they show up in the
  Gallery/Photos app like any other video.

## How it's built

- **UI:** Jetpack Compose, single-activity, no navigation library — just one
  `AppScreen` sealed class switching between a handful of composable screens
  (`HomeScreen`, `VideoTrimScreen`, `MergeScreen`, `DenoiseScreen`).
- **Video playback:** [Media3 ExoPlayer](https://developer.android.com/media/media3).
- **Video processing (trim/merge/cut-middle):**
  [Media3 Transformer](https://developer.android.com/media/media3/transformer), using
  `EditedMediaItem` clipping configurations and `EditedMediaItemSequence` to splice
  clips together without re-implementing any encoding/muxing by hand.
- **Background noise removal:** a custom `AudioProcessor`
  (`RNNoiseAudioProcessor`) plugged into Transformer's effects pipeline. It
  downmixes to mono, resamples to 48kHz (simple linear interpolation), batches audio
  into RNNoise's fixed 480-sample frames, and runs each frame through RNNoise via a
  small JNI bridge to the real Xiph.Org C library (compiled from source with the
  Android NDK/CMake — not a Java/Kotlin port).

## Project structure

```
app/src/main/java/com/bilal/simplevideoeditorapp/
├── MainActivity.kt              # All UI: screens, dialogs, navigation state
└── util/
    ├── Utility.kt                # Time parsing, clip-range math, and the three
    │                              # export functions (trim / merge / denoise)
    ├── RNNoise.kt                 # Kotlin wrapper around the native RNNoise JNI calls
    └── RNNoiseAudioProcessor.kt   # Media3 AudioProcessor: resampling + RNNoise framing

app/src/main/cpp/
├── CMakeLists.txt                # Builds RNNoise (static lib) + the JNI bridge (shared lib)
├── rnnoise-jni.cpp               # JNI glue between Kotlin and RNNoise's C API
└── rnnoise/                      # Vendored RNNoise source (see setup below) - not committed,
                                   # see .gitignore note
    ├── include/rnnoise.h
    └── src/*.c, *.h
```

## Setup: building RNNoise from source

The native RNNoise library isn't checked into this repo (its trained model weights
alone are ~56MB and are fetched from Xiph's own servers at build time — see their
[repo](https://github.com/xiph/rnnoise) for why). You need to build it once and
vendor the output into this project before Android Studio can compile the app.

1. **Clone and build RNNoise** on Linux/macOS/WSL:
   ```bash
   git clone https://github.com/xiph/rnnoise.git
   cd rnnoise
   ./autogen.sh   # downloads the pretrained model (~56MB) automatically
   ./configure
   make
   ```
2. **Copy the following into this project**, under `app/src/main/cpp/rnnoise/`:
   - `include/rnnoise.h` → `app/src/main/cpp/rnnoise/include/`
   - From `src/`: `denoise.c`, `rnn.c`, `pitch.c`, `kiss_fft.c`, `celt_lpc.c`, `nnet.c`,
     `nnet_default.c`, `parse_lpcnet_weights.c`, `rnnoise_data.c`, `rnnoise_tables.c`,
     plus every matching `.h` header next to them (`arch.h`, `celt_lpc.h`, `common.h`,
     `cpu_support.h`, `denoise.h`, `_kiss_fft_guts.h`, `kiss_fft.h`, `nnet.h`,
     `nnet_arch.h`, `opus_types.h`, `pitch.h`, `rnn.h`, `rnnoise_data.h`, `vec.h`,
     `vec_neon.h`) → `app/src/main/cpp/rnnoise/src/`
   - The whole `src/x86/` folder (`x86_arch_macros.h`, `x86cpu.h`, `dnn_x86.h`) →
     `app/src/main/cpp/rnnoise/src/x86/` — these are just shared macro headers pulled
     in unconditionally by `vec.h`; no x86 code actually runs on the phone.
   - **Skip:** `src/x86/*.c`, `torch/`, `training/`, `scripts/`, `examples/`, `doc/`,
     `m4/`, and the autotools files (`Makefile.am`, `autogen.sh`, etc.) — none of that
     is needed to run the library.
   - Use `rnnoise_data.c`/`.h` (the real model), **not** `rnnoise_data_little.c`/`.h`.
3. Open the project in Android Studio and let Gradle/CMake sync — it will compile
   RNNoise and the JNI bridge into a native library automatically from that point on.

## Requirements

- Android Studio (current stable), NDK + CMake components installed
- `minSdk` targeting devices with `arm64-v8a` or `armeabi-v7a` (the native build only
  targets ARM; there's no x86/emulator support for the noise-removal feature)

## Design notes / limitations

- This project intentionally skips clean architecture / MVVM — it's a small personal
  tool, not a production app, and the code favors simplicity and readability over
  structure.
- The noise-removal feature suppresses *steady* background noise (fans, wind, hum,
  hiss). It won't separate out other voices or remove loud, non-steady sounds.
- The audio resampler used before RNNoise is a simple linear interpolator — good
  enough to feed a noise suppressor, not intended to be audiophile-grade.

## Credits

- [RNNoise](https://github.com/xiph/rnnoise) by Jean-Marc Valin / Xiph.Org Foundation
  (BSD license) — the neural noise-suppression library at the core of the background
  noise removal feature.
- [Media3](https://developer.android.com/media/media3) (ExoPlayer + Transformer) by
  Google, for all video playback and processing.
