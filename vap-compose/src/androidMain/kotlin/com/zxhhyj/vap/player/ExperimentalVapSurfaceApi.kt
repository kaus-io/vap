package com.zxhhyj.vap.player

/**
 * Opt-in marker for the Android-only Surface direct render API.
 *
 * Android 独占的 Surface 直绘 API 的 opt-in 标记。
 *
 * Surface present lifecycle and compositing semantics are still being tuned; consumers must
 * explicitly accept that the API may change by adding `@OptIn(ExperimentalVapSurfaceApi::class)`.
 * Promoted to [RequiresOptIn.Level.ERROR] so accidental use fails at compile time rather than
 * shipping silently.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "VAP Surface direct render is experimental (Android only); lifecycle and " +
        "compositing semantics may change. Opt in with @OptIn(ExperimentalVapSurfaceApi::class).",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalVapSurfaceApi
