package com.tracktosearch.ui.screen.swiftie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.local.TicketStubStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 纪念页手链上要串的昵称。
 *
 * 两个来源按优先级取：登录态的昵称最新，取票时存下的票根昵称是离线也拿得到的那份。
 * 两个都空就返回 null，手链只挂两条（见 `SwiftieBracelet`）。
 *
 * 单独一个 ViewModel 而不是塞进现成的：彩蛋页除了这一个字符串不需要任何仓库，
 * 挂一个大 ViewModel 会把它那一串初始化也一起拖进来。
 */
@HiltViewModel
class SwiftieNicknameViewModel @Inject constructor(
    authManager: AuthManager,
    ticketStubStorage: TicketStubStorage
) : ViewModel() {

    val nickname: StateFlow<String?> = combine(
        authManager.nickname,
        ticketStubStorage.stub
    ) { account, stub ->
        account?.takeIf { it.isNotBlank() } ?: stub?.nickname?.takeIf { it.isNotBlank() }
    }.stateIn(
        scope = viewModelScope,
        // 彩蛋整段两分多钟都在前台，用不着 WhileSubscribed 的超时；Eagerly 让票根那次
        // DataStore 读在页面刚打开时就发起，等手链落下来（T107s）时早就到位了
        started = SharingStarted.Eagerly,
        initialValue = null
    )
}
