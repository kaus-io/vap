package com.zxhhyj.vap.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxhhyj.vap.encode.DefaultVapEncoder
import com.zxhhyj.vap.encode.EncodeProgress
import com.zxhhyj.vap.encode.EncodeRequest
import com.zxhhyj.vap.encode.HasAlpha
import com.zxhhyj.vap.encode.Quality
import com.zxhhyj.vap.encode.VideoCodec
import com.zxhhyj.vap.tool.generated.resources.Res
import com.zxhhyj.vap.tool.generated.resources.alpha
import com.zxhhyj.vap.tool.generated.resources.alpha_off
import com.zxhhyj.vap.tool.generated.resources.alpha_on
import com.zxhhyj.vap.tool.generated.resources.app_subtitle
import com.zxhhyj.vap.tool.generated.resources.app_title
import com.zxhhyj.vap.tool.generated.resources.bitrate_kbps
import com.zxhhyj.vap.tool.generated.resources.browse
import com.zxhhyj.vap.tool.generated.resources.cancel
import com.zxhhyj.vap.tool.generated.resources.codec
import com.zxhhyj.vap.tool.generated.resources.codec_h264
import com.zxhhyj.vap.tool.generated.resources.codec_h265
import com.zxhhyj.vap.tool.generated.resources.create_vap
import com.zxhhyj.vap.tool.generated.resources.crf_value
import com.zxhhyj.vap.tool.generated.resources.ffmpeg_path
import com.zxhhyj.vap.tool.generated.resources.fps
import com.zxhhyj.vap.tool.generated.resources.input_folder
import com.zxhhyj.vap.tool.generated.resources.open_output
import com.zxhhyj.vap.tool.generated.resources.quality
import com.zxhhyj.vap.tool.generated.resources.quality_bitrate
import com.zxhhyj.vap.tool.generated.resources.quality_crf
import com.zxhhyj.vap.tool.generated.resources.quality_vbr
import com.zxhhyj.vap.tool.generated.resources.scale
import com.zxhhyj.vap.tool.generated.resources.status_cancelled
import com.zxhhyj.vap.tool.generated.resources.status_done
import com.zxhhyj.vap.tool.generated.resources.status_error
import com.zxhhyj.vap.tool.generated.resources.vbr_max_kbps
import com.zxhhyj.vap.tool.generated.resources.vbr_target_kbps
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.io.File

/**
 * Compose UI for `app-vap-tool`. Holds the form state, drives [DefaultVapEncoder]
 * via a coroutine job, and reports progress / status / cancellation through the
 * [EncodeProgress] flow collected in [job].
 *
 * `app-vap-tool` 的 Compose UI。持有表单状态,通过协程 job 驱动 [DefaultVapEncoder],
 * 借助 [EncodeProgress] 流程上报进度、状态与取消事件。
 */
@Composable
fun ToolApp(
    onEncodeSuccess: (videoPath: String) -> Unit = {},
) {
    MaterialTheme {
        var inputDir by remember { mutableStateOf("") }
        var fps by remember { mutableStateOf("60") }
        var scale by remember { mutableStateOf("0.5") }
        var codecH265 by remember { mutableStateOf(false) }
        var qualityMode by remember { mutableStateOf(QualityUiMode.Bitrate) }
        var bitrate by remember { mutableStateOf("3000") }
        var vbrTarget by remember { mutableStateOf("3000") }
        var vbrMax by remember { mutableStateOf("4500") }
        var crf by remember { mutableStateOf("29") }
        var ffmpegPath by remember { mutableStateOf("ffmpeg") }
        var hasAlphaChoice by remember { mutableStateOf<Boolean?>(null) }
        var status by remember { mutableStateOf<String?>(null) }
        var progress by remember { mutableFloatStateOf(0f) }
        var running by remember { mutableStateOf(false) }
        var lastOutput by remember { mutableStateOf<String?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val encoder = remember { DefaultVapEncoder() }
        val statusCancelled = stringResource(Res.string.status_cancelled)
        val directoryPicker = rememberDirectoryPickerLauncher { directory ->
            if (directory != null) {
                inputDir = directory.absolutePath()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(Res.string.app_title), style = MaterialTheme.typography.h6)
            Text(stringResource(Res.string.app_subtitle))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputDir,
                    onValueChange = { inputDir = it },
                    label = { Text(stringResource(Res.string.input_folder)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                TextButton(onClick = { directoryPicker.launch() }) {
                    Text(stringResource(Res.string.browse))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fps,
                    onValueChange = { fps = it },
                    label = { Text(stringResource(Res.string.fps)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = scale,
                    onValueChange = { scale = it },
                    label = { Text(stringResource(Res.string.scale)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.codec))
                RadioButton(selected = !codecH265, onClick = { codecH265 = false })
                Text(stringResource(Res.string.codec_h264))
                RadioButton(selected = codecH265, onClick = { codecH265 = true })
                Text(stringResource(Res.string.codec_h265))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.alpha))
                RadioButton(
                    selected = hasAlphaChoice == true,
                    onClick = { hasAlphaChoice = true },
                )
                Text(stringResource(Res.string.alpha_on))
                RadioButton(
                    selected = hasAlphaChoice == false,
                    onClick = { hasAlphaChoice = false },
                )
                Text(stringResource(Res.string.alpha_off))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.quality))
                RadioButton(
                    selected = qualityMode == QualityUiMode.Bitrate,
                    onClick = { qualityMode = QualityUiMode.Bitrate },
                )
                Text(stringResource(Res.string.quality_bitrate))
                RadioButton(
                    selected = qualityMode == QualityUiMode.Vbr,
                    onClick = { qualityMode = QualityUiMode.Vbr },
                )
                Text(stringResource(Res.string.quality_vbr))
                RadioButton(
                    selected = qualityMode == QualityUiMode.Crf,
                    onClick = { qualityMode = QualityUiMode.Crf },
                )
                Text(stringResource(Res.string.quality_crf))
            }

            when (qualityMode) {
                QualityUiMode.Crf -> OutlinedTextField(
                    value = crf,
                    onValueChange = { crf = it },
                    label = { Text(stringResource(Res.string.crf_value)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                QualityUiMode.Bitrate -> OutlinedTextField(
                    value = bitrate,
                    onValueChange = { bitrate = it },
                    label = { Text(stringResource(Res.string.bitrate_kbps)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                QualityUiMode.Vbr -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = vbrTarget,
                        onValueChange = { vbrTarget = it },
                        label = { Text(stringResource(Res.string.vbr_target_kbps)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = vbrMax,
                        onValueChange = { vbrMax = it },
                        label = { Text(stringResource(Res.string.vbr_max_kbps)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
            }

            OutlinedTextField(
                value = ffmpegPath,
                onValueChange = { ffmpegPath = it },
                label = { Text(stringResource(Res.string.ffmpeg_path)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (running) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && inputDir.isNotBlank() && hasAlphaChoice != null,
                    onClick = {
                        status = null
                        progress = 0f
                        lastOutput = null
                        running = true
                        val request = EncodeRequest(
                            inputDir = inputDir,
                            fps = fps.toIntOrNull() ?: 60,
                            scale = scale.toFloatOrNull() ?: 0.5f,
                            codec = if (codecH265) VideoCodec.H265 else VideoCodec.H264,
                            quality = when (qualityMode) {
                                QualityUiMode.Crf -> Quality.Crf(crf.toIntOrNull() ?: 29)
                                QualityUiMode.Bitrate -> Quality.Bitrate(
                                    bitrate.toIntOrNull() ?: 3000
                                )

                                QualityUiMode.Vbr -> {
                                    val target = vbrTarget.toIntOrNull() ?: 3000
                                    val max = vbrMax.toIntOrNull() ?: (target * 3 / 2)
                                    Quality.Vbr(
                                        targetKbps = target,
                                        maxKbps = max.coerceAtLeast(target)
                                    )
                                }
                            },
                            ffmpegPath = ffmpegPath,
                            hasAlpha = if (hasAlphaChoice == true) HasAlpha.On else HasAlpha.Off,
                        )
                        // Build the request from the current form snapshot, then launch
                        // a single encode coroutine. `job` is tracked so the Cancel
                        // button can interrupt the flow mid-encode.
                        // 按当前表单快照构造 request,启动单条 encode 协程;
                        // 持有 `job` 以便 Cancel 按钮在编码中途取消。
                        job = scope.launch {
                            encoder.encode(request).collect { event ->
                                when (event) {
                                    is EncodeProgress.Running -> progress = event.fraction
                                    is EncodeProgress.Warning -> Unit
                                    is EncodeProgress.Success -> {
                                        progress = 1f
                                        lastOutput = event.result.videoPath
                                        status = getString(
                                            Res.string.status_done,
                                            event.result.videoPath
                                        )
                                        running = false
                                        onEncodeSuccess(event.result.videoPath)
                                    }

                                    is EncodeProgress.Failed -> {
                                        status = getString(Res.string.status_error, event.message)
                                        running = false
                                    }
                                }
                            }
                            running = false
                        }
                    },
                ) { Text(stringResource(Res.string.create_vap)) }

                Button(
                    enabled = running,
                    onClick = {
                        job?.cancel()
                        running = false
                        status = statusCancelled
                    },
                ) { Text(stringResource(Res.string.cancel)) }

                Button(
                    enabled = lastOutput != null,
                    onClick = {
                        val path = lastOutput ?: return@Button
                        val file = File(path).parentFile ?: return@Button
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(file)
                        }
                    },
                ) { Text(stringResource(Res.string.open_output)) }
            }

            status?.let { Text(it) }
        }
    }
}

// UI-only enum that mirrors the CLI's `--quality` choices. Kept private to ToolApp
// because the actual encoder speaks in [Quality] sealed classes.
// UI 专用枚举,与 CLI 的 `--quality` 选项一一对应。仅在 ToolApp 内使用,
// 真正传给编码器的是 [Quality] 密封类。
private enum class QualityUiMode {
    Bitrate,
    Vbr,
    Crf,
}
