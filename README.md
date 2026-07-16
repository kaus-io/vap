# VAP (Kotlin Multiplatform)

> A Kotlin Multiplatform reimplementation of
> Tencent's [VAP (Video Animation Player)](https://github.com/Tencent/vap), targeting Android and
> Desktop (JVM) with a Compose Multiplatform playback API and a Compose Desktop authoring tool.

[中文文档](./README.zh.md)

## Overview

VAP is a video container with a transparent alpha channel, designed to play short, richly animated
effects with hardware-accelerated decoding. Tencent's original implementation is Android-only; this
project ports the format to a KMP-friendly stack and adds a Compose-based playback layer with a
clean `load → drive → render` API.

The VAP container layout and the encode / decode pipeline are based on the design of
Tencent's [VAP (Video Animation Player)](https://github.com/Tencent/vap).

## Modules

| Module                                         | Role                                                 | Coordinate               |
|------------------------------------------------|------------------------------------------------------|--------------------------|
| [`vap-core`](./vap-core)                       | models / `VapSource`                                 | `com.zxhhyj:vap-core`    |
| [`vap-decode`](./vap-decode)                   | vapc parsing (`VapcParser`)                          | `com.zxhhyj:vap-decode`  |
| [`vap-encode`](./vap-encode)                   | PNG → VAP authoring pipeline (KMP)                   | `com.zxhhyj:vap-encode`  |
| [`vap-compose`](./vap-compose)                 | Compose `VapAnimation`; Android GLES; Desktop FFmpeg | `com.zxhhyj:vap-compose` |
| [`example-vap-shared`](./example-vap-shared)   | Demo UI                                              | no                       |
| [`example-vap-android`](./example-vap-android) | Android host                                         | no                       |
| [`example-vap-desktop`](./example-vap-desktop) | Desktop host                                         | no                       |
| [`example-vap-tool`](./example-vap-tool)       | Compose Desktop authoring tool                       | no                       |

```kotlin
implementation("com.zxhhyj:vap-core:<version>")
implementation("com.zxhhyj:vap-decode:<version>")
implementation("com.zxhhyj:vap-encode:<version>")
implementation("com.zxhhyj:vap-compose:<version>")
```

## Compose playback

Three layers: **load** → **drive (decode session)** → **render**. To survive page transitions, mount
the composition and state above the disposable subtree and only toggle `isPlaying`.

### Recommended: Split (resident, pausable)

```kotlin
val composition by rememberVapComposition(VapCompositionSpec.File(pathToMp4))
val anim = animateVapCompositionAsState(
    composition,
    iterations = VapConstants.IterateForever,
    isPlaying = pageVisible,
)
VapAnimation(anim, modifier = Modifier.fillMaxWidth().height(320.dp))
```

### Convenience: Combined overload (one-liner)

The state is bound to the composable's subtree lifetime. **Do not** use this when you need
keep-alive across page switches.

```kotlin
val composition by rememberVapComposition(VapCompositionSpec.File(pathToMp4))
VapAnimation(
    composition = composition,
    iterations = VapConstants.IterateForever,
    modifier = Modifier.fillMaxWidth().height(320.dp),
)
```

## Build / run

```sh
./gradlew :example-vap-android:assembleDebug
./gradlew :example-vap-desktop:run
./gradlew :example-vap-tool:run
./gradlew printPublishableModules
```

The authoring tool needs a local `ffmpeg`.

## License

This project is licensed under the [MIT License](LICENSE).
