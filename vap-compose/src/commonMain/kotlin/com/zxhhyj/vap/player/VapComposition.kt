package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.zxhhyj.vap.decode.VapcParser
import com.zxhhyj.vap.decode.parseMp4File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Loaded VAP. Marked `@Stable` (not `@Immutable`) because [source] may be
 * `VapSource.Bytes` whose Snapshot state can change via `VapSource.Bytes.update`.
 *
 * 已加载的 VAP。标注为 `@Stable`（而非 `@Immutable`）是因为 [source] 可能是 `VapSource.Bytes`，
 * 其 Snapshot 状态会通过 `VapSource.Bytes.update` 发生变化。
 */
@Stable
public class VapComposition internal constructor(
    public val config: VapConfig,
    internal val source: VapSource,
)


/**
 * Blocking load: parses the MP4 header and wraps the spec into a `VapSource`.
 * Prefer [loadVapCompositionAsync] / [rememberVapComposition] from a Composable.
 *
 * 阻塞加载：解析 MP4 头部并将 spec 包装为 `VapSource`。在 Composable 中请优先使用
 * [loadVapCompositionAsync] 或 [rememberVapComposition]。
 */
public fun loadVapComposition(spec: VapCompositionSpec): VapComposition {
    val source = spec.toVapSource()
    val config = when (spec) {
        is VapCompositionSpec.File -> parseMp4File(spec.path)
        is VapCompositionSpec.Bytes -> VapcParser.parseMp4(spec.data)
    }
    return VapComposition(config, source)
}


public fun loadVapComposition(source: VapSource): VapComposition =
    loadVapComposition(source.toCompositionSpec())

/**
 * Blocking parse/IO on `Dispatchers.IO`, cancellable via `runInterruptible`
 * (interruptible reads in `parseMp4File` / byte parsing).
 *
 * 在 `Dispatchers.IO` 上执行阻塞解析/IO，可通过 `runInterruptible` 取消
 * （`parseMp4File` / 字节解析支持中断读取）。
 */
public suspend fun loadVapCompositionAsync(spec: VapCompositionSpec): VapComposition =
    withContext(Dispatchers.IO) {
        runInterruptible {
            loadVapComposition(spec)
        }
    }

public suspend fun loadVapCompositionAsync(source: VapSource): VapComposition =
    loadVapCompositionAsync(source.toCompositionSpec())


/**
 * Remember a [VapComposition] across recompositions; reloads in the background when [spec] changes.
 *
 * 在多次重组之间记住 [VapComposition]；当 [spec] 变更时在后台重新加载。
 *
 * @param spec File or in-memory MP4 bytes.
 * @param onError Receives non-cancellation failures; defaults to re-throwing so the caller sees them.
 * @return A Compose `State` holding `null` until the first load completes.
 */
@Composable
public fun rememberVapComposition(
    spec: VapCompositionSpec,
    onError: ((Throwable) -> Unit)? = null,
): State<VapComposition?> {
    val state = remember(spec) { mutableStateOf<VapComposition?>(null) }
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(spec) {
        // Keep previous composition while reloading so consumers do not see a null flash.
        // 重新加载期间保留旧 composition，避免消费者看到 null 闪烁。
        try {
            state.value = loadVapCompositionAsync(spec)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            state.value = null
            currentOnError?.invoke(t) ?: throw t
        }
    }

    return state
}
