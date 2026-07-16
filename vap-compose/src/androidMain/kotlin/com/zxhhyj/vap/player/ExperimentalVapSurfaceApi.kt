package com.zxhhyj.vap.player


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
