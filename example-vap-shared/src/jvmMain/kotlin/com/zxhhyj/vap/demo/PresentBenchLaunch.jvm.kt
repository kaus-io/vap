package com.zxhhyj.vap.demo

/**
 * JVM `actual` of [logVapBench]: writes the bench log line to stdout with the shared
 * `LOG_TAG` prefix so it can be `tee`'d or grepped from the Gradle run console.
 *
 * JVM 平台 [logVapBench] 的 actual 实现:将 bench 日志写入标准输出并加统一
 * `LOG_TAG` 前缀,便于从 Gradle 运行控制台直接 grep / tee。
 */
internal actual fun logVapBench(message: String) {
    println("${PresentBenchLaunch.LOG_TAG} $message")
}
