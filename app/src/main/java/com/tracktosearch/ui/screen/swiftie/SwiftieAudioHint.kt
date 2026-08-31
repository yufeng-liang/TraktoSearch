package com.tracktosearch.ui.screen.swiftie

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Earbuds
import com.tracktosearch.R
import kotlinx.coroutines.delay

/**
 * 播放前的音频环境建议。
 *
 * 两个条件独立成四种结果 —— 已经戴着耳机的人不该被劝「戴上耳机」，
 * 所以不合成一条通用文案。
 */
internal enum class SwiftieAudioAdvice {
    /** 有声且已戴耳机：什么都不提示。 */
    NONE,

    /** 声音开着，但没戴耳机。 */
    HEADPHONES,

    /** 耳机连着，但静音档或媒体音量为 0。 */
    VOLUME,

    /** 两样都缺。 */
    VOLUME_AND_HEADPHONES
}

/**
 * 算「戴着耳机」的输出设备类型。
 *
 * 有线、USB、蓝牙、助听器、LE Audio 都算 —— 判断的是「声音会不会外放出去」，
 * 不是「是不是耳塞」。新增的两个类型按 API 等级加，避免 lint 的 InlinedApi。
 */
private val HEADPHONE_TYPES: Set<Int> = buildSet {
    add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
    add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
    add(AudioDeviceInfo.TYPE_USB_HEADSET)
    add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        add(AudioDeviceInfo.TYPE_HEARING_AID)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}

/**
 * 轮询间隔。
 *
 * 系统**没有**公开的媒体音量监听接口（`VOLUME_CHANGED_ACTION` 不是公开 API），
 * 而题面只在屏上待几十秒，轮询比注册两套回调再自己合流省事得多。
 */
private const val AUDIO_POLL_MS = 700L

private fun AudioManager.hasHeadphones(): Boolean = runCatching {
    getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
}.getOrDefault(false)

private fun AudioManager.readAdvice(): SwiftieAudioAdvice {
    val silenced = isSwiftieSilenced()
    val headphones = hasHeadphones()
    return when {
        silenced && !headphones -> SwiftieAudioAdvice.VOLUME_AND_HEADPHONES
        silenced -> SwiftieAudioAdvice.VOLUME
        !headphones -> SwiftieAudioAdvice.HEADPHONES
        else -> SwiftieAudioAdvice.NONE
    }
}

/**
 * 题面期间持续跟踪音频环境。
 *
 * 初值在 `remember` 里同步读出来，所以第一帧就是对的 —— 放到 `LaunchedEffect`
 * 里读会让提示晚一帧弹出来。之后每 [AUDIO_POLL_MS] 复查一次：用户中途插上耳机
 * 或调大音量，提示要自己消失。
 */
@Composable
internal fun rememberSwiftieAudioAdvice(): SwiftieAudioAdvice {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var advice by remember(audioManager) {
        mutableStateOf(audioManager?.readAdvice() ?: SwiftieAudioAdvice.NONE)
    }
    LaunchedEffect(audioManager) {
        if (audioManager == null) return@LaunchedEffect
        while (true) {
            delay(AUDIO_POLL_MS)
            advice = audioManager.readAdvice()
        }
    }
    return advice
}

/**
 * 「开声音 / 戴耳机效果更好」的一行提示，图标是无线耳机。
 *
 * [SwiftieAudioAdvice.NONE] 时整块不组合。刻意**不做淡出** ——
 * 插上耳机就立刻消失是更明确的反馈，而退场动画期间 advice 已经是 NONE、
 * 没有对应文案可读。
 *
 * 用色走 `onSurfaceVariant` 而**不是** [SwiftiePalette] ——
 * 这一行落在题面的 `surface` 底上（水彩天空只在灯箱那一块里），
 * 皇家蓝在深色模式下就是深蓝压深底，读不出来。
 */
@Composable
internal fun SwiftieAudioHint(
    advice: SwiftieAudioAdvice,
    modifier: Modifier = Modifier
) {
    val labelRes = when (advice) {
        SwiftieAudioAdvice.NONE -> return
        SwiftieAudioAdvice.HEADPHONES -> R.string.swiftie_audio_hint_headphones
        SwiftieAudioAdvice.VOLUME -> R.string.swiftie_audio_hint_volume
        SwiftieAudioAdvice.VOLUME_AND_HEADPHONES -> R.string.swiftie_audio_hint_all
    }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Earbuds,
            // 文案已经把意思说全了，图标对 TalkBack 是装饰
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(labelRes),
            style = TextStyle(
                fontSize = 12.sp,
                color = tint,
                textAlign = TextAlign.Center
            ),
            // 韩文那句最长，360dp 宽的屏上会折行。调用方按两行预留了位子
            maxLines = 2
        )
    }
}
