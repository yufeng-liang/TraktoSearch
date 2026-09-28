package com.tracktosearch.ui.navigation

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.util.ConnectivityObserver.NetworkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * 授权状态提示：设备在线、但授权服务暂时校验不通时才提示。
 *
 * 文案与主页离线横幅分开：横幅说的是「设备断网」，这里说的是「授权没校验上」。
 * 两者成因不同却共用同一句「当前处于离线模式」，会让网络正常的用户收到离线误报。
 *
 * 延迟确认：冷启动/后台校验的网络抖动会短暂置 OFFLINE 随后恢复 AUTHORIZED，
 * 只有状态稳定为 OFFLINE 才提示。
 */
@Composable
internal fun AuthNetworkNotice(
    authState: StateFlow<AuthState>,
    networkStatus: StateFlow<NetworkStatus>,
    snackbarHostState: SnackbarHostState,
) {
    val currentAuthState by authState.collectAsState()
    val currentNetworkStatus by networkStatus.collectAsState()
    val noticeMessage = stringResource(R.string.auth_verify_unavailable)

    // 网络状态参与 key：设备断网时 effect 重跑、上一条 Snackbar 随之收起（横幅已在讲这件事），
    // 网络恢复后重新判断是否仍然校验不通。
    LaunchedEffect(currentAuthState, currentNetworkStatus) {
        if (currentAuthState != AuthState.OFFLINE ||
            currentNetworkStatus == NetworkStatus.OFFLINE
        ) {
            return@LaunchedEffect
        }
        delay(3_000)
        if (authState.value == AuthState.OFFLINE &&
            networkStatus.value != NetworkStatus.OFFLINE
        ) {
            // 不用 scope.launch：留在本 effect 里，网络/鉴权状态一变 effect 取消，Snackbar 随之收起
            snackbarHostState.showSnackbar(
                message = noticeMessage,
                duration = SnackbarDuration.Long,
            )
        }
    }
}
