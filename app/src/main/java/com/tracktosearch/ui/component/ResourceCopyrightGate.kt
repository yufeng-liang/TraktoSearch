package com.tracktosearch.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.tracktosearch.data.local.ResourceCopyrightStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 为资源版权守卫提供 DataStore 读写；ViewModel 生命周期与当前导航条目一致。 */
@HiltViewModel
class ResourceCopyrightViewModel @Inject constructor(
    private val storage: ResourceCopyrightStorage
) : ViewModel() {
    suspend fun awaitDismissed(): Boolean = storage.awaitDismissed()

    suspend fun setDismissed(dismissed: Boolean) {
        storage.setDismissed(dismissed)
    }
}

internal data class PendingResourceAction(
    val action: ResourceCopyrightAction,
    val onConfirmed: () -> Unit
)

/**
 * 资源卡片的统一动作守卫。
 *
 * 调用 [request] 时先等待本机首值；未勾选免提示则挂起弹窗，
 * 取消不执行动作，确认后才按需写盘并执行原动作。
 */
@Stable
class ResourceCopyrightRequester internal constructor(
    private val viewModel: ResourceCopyrightViewModel,
    private val scope: CoroutineScope,
    private val pendingState: MutableState<PendingResourceAction?>
) {
    fun request(action: ResourceCopyrightAction, onConfirmed: () -> Unit) {
        scope.launch {
            if (viewModel.awaitDismissed()) {
                onConfirmed()
                return@launch
            }
            if (pendingState.value == null) {
                pendingState.value = PendingResourceAction(action, onConfirmed)
            }
        }
    }

    @Composable
    fun Host() {
        val pending = pendingState.value ?: return
        ResourceCopyrightDialog(
            action = pending.action,
            onConfirm = { dontShowAgain ->
                pendingState.value = null
                scope.launch {
                    if (dontShowAgain) {
                        viewModel.setDismissed(true)
                    }
                    pending.onConfirmed()
                }
            },
            onDismiss = { pendingState.value = null }
        )
    }
}

@Composable
fun rememberResourceCopyrightRequester(
    viewModel: ResourceCopyrightViewModel = hiltViewModel()
): ResourceCopyrightRequester {
    val scope = rememberCoroutineScope()
    val pendingState = remember { mutableStateOf<PendingResourceAction?>(null) }
    return remember(viewModel, scope) {
        ResourceCopyrightRequester(viewModel, scope, pendingState)
    }
}
