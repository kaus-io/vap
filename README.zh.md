# VAP (Kotlin Multiplatform) — 中文

## 简介

VAP 是一种带透明 alpha 通道的视频容器，常用于播放短小但效果丰富的动画，并通过硬件加速解码保证流畅。Tencent
官方的实现仅支持 Android；本项目将其格式移植到 KMP 技术栈，并提供一套 Compose 播放 API，加载 / 驱动 /
渲染三段式，与常见的 Compose 动画调用习惯一致。

VAP 容器格式与编/解码思路参考自 Tencent
的 [VAP (Video Animation Player)](https://github.com/Tencent/vap) 项目。

## 模块

| 模块                                             | 作用                                                 | 坐标                       |
|------------------------------------------------|----------------------------------------------------|--------------------------|
| [`vap-core`](./vap-core)                       | 模型 / `VapSource`                                   | `com.zxhhyj:vap-core`    |
| [`vap-decode`](./vap-decode)                   | vapc 解析 (`VapcParser`)                             | `com.zxhhyj:vap-decode`  |
| [`vap-encode`](./vap-encode)                   | PNG → VAP 制作管线 (KMP)                               | `com.zxhhyj:vap-encode`  |
| [`vap-compose`](./vap-compose)                 | Compose `VapAnimation`；Android GLES；Desktop FFmpeg | `com.zxhhyj:vap-compose` |
| [`example-vap-shared`](./example-vap-shared)   | 示例 UI                                              | no                       |
| [`example-vap-android`](./example-vap-android) | Android 宿主                                         | no                       |
| [`example-vap-desktop`](./example-vap-desktop) | Desktop 宿主                                         | no                       |
| [`example-vap-tool`](./example-vap-tool)       | Compose Desktop 制作工具                               | no                       |

```kotlin
implementation("com.zxhhyj:vap-core:<version>")
implementation("com.zxhhyj:vap-decode:<version>")
implementation("com.zxhhyj:vap-encode:<version>")
implementation("com.zxhhyj:vap-compose:<version>")
```

## Compose 播放

三层：**加载** → **驱动（解码会话）** → **渲染**。需要切页保活时，把 composition + state 挂在不会随页面
dispose 的祖先上，只改 `isPlaying`。

### 主路径：Split（常驻可暂停）

```kotlin
val composition by rememberVapComposition(VapCompositionSpec.File(pathToMp4))
val anim = animateVapCompositionAsState(
    composition,
    iterations = VapConstants.IterateForever,
    isPlaying = pageVisible,
)
VapAnimation(anim, modifier = Modifier.fillMaxWidth().height(320.dp))
```

### 次要：合并重载（简单一键播）

state 生命周期绑定该 Composable 子树；**不要**用在需要切页保活的场景。

```kotlin
val composition by rememberVapComposition(VapCompositionSpec.File(pathToMp4))
VapAnimation(
    composition = composition,
    iterations = VapConstants.IterateForever,
    modifier = Modifier.fillMaxWidth().height(320.dp),
)
```

## 构建 / 运行

```sh
./gradlew :example-vap-android:assembleDebug
./gradlew :example-vap-desktop:run
./gradlew :example-vap-tool:run
./gradlew printPublishableModules
```

制作工具需要本机 `ffmpeg`。

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
