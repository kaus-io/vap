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
 * Loaded VAP. Marked [Stable] (not [Immutable]) because [source] may be
 * [VapSource.Bytes] whose Snapshot state can change via [VapSource.Bytes.update].
 */
@Stable
public class VapComposition internal constructor(
    public val config: VapConfig,
    internal val source: VapSource,
)


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
 * Blocking parse/IO on [Dispatchers.IO], cancellable via [runInterruptible]
 * (interruptible reads in [parseMp4File] / byte parsing).
 */
public suspend fun loadVapCompositionAsync(spec: VapCompositionSpec): VapComposition =
    withContext(Dispatchers.IO) {
        runInterruptible {
            loadVapComposition(spec)
        }
    }

public suspend fun loadVapCompositionAsync(source: VapSource): VapComposition =
    loadVapCompositionAsync(source.toCompositionSpec())


@Composable
public fun rememberVapComposition(
    spec: VapCompositionSpec,
    onError: ((Throwable) -> Unit)? = null,
): State<VapComposition?> {
    val state = remember(spec) { mutableStateOf<VapComposition?>(null) }
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(spec) {
        // Keep previous composition while reloading so consumers do not see a null flash.
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
