package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.zxhhyj.vap.decode.VapcParser
import com.zxhhyj.vap.decode.parseMp4File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
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


@Composable
public fun rememberVapComposition(
    spec: VapCompositionSpec,
    onError: ((Throwable) -> Unit)? = null,
): State<VapComposition?> {
    val state = remember(spec) { mutableStateOf<VapComposition?>(null) }

    LaunchedEffect(spec) {
        state.value = null
        try {
            state.value = withContext(Dispatchers.IO) {
                loadVapComposition(spec)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            state.value = null
            onError?.invoke(t) ?: throw t
        }
    }

    return state
}
