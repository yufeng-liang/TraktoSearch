package com.tracktosearch.ui.screen.ai

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * 取跨页面共享的精灵 ViewModel。
 *
 * 搜索页、Trakt 搜索页、详情页在 NavHost 里是三个独立目的地，各自 `hiltViewModel()`
 * 会按 NavBackStackEntry 作用域拿到三个互不相认的实例，后果是：
 *
 * - 详情页那份的 activatedCharacterId 永远是 null，加入看单的场景一次都不会出现；
 * - 两个搜索页各要激活一次，激活态、答题进度、口味结果全都各算一份；
 * - spriteSessionId 按实例生成，会话配额被放大成实例个数倍。
 *
 * 统一挂到 Activity 的 ViewModelStore 上，全 App 一份状态。会话配额语义由
 * [AiSpriteViewModel.onSpriteCenterOpened] 在每次打开精灵中心时轮换会话 ID 来保证。
 *
 * Activity 取不到时（Compose 预览等）退回默认作用域，只影响预览。
 */
@Composable
fun rememberSharedAiSpriteViewModel(): AiSpriteViewModel {
    val owner: ViewModelStoreOwner = LocalActivity.current as? ViewModelStoreOwner
        ?: checkNotNull(LocalViewModelStoreOwner.current) {
            "AiSpriteViewModel 需要一个 ViewModelStoreOwner"
        }
    return hiltViewModel(owner)
}
