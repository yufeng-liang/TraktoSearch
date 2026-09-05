package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ticketStubDataStore: DataStore<Preferences> by preferencesDataStore(name = "ticket_stub")

/**
 * 取票成功后的票根落盘。
 *
 * 有票根，用户下次回到取票页就能直接看到自己那张票，不用等网络回来才知道取过票没有。
 *
 * [TicketStub.issuedEpochDay] 存的是取票当天，不是读取当天：票一旦印出来，上面的日期
 * 就不该再变，否则今天看到的是今天、明天打开又变成明天，票根也就不成票根了。所以这个类
 * 只管存取，不在内部取当天日期，日期由调用方在取票那一刻算好传进来。
 */
@Singleton
class TicketStubStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_NICKNAME = stringPreferencesKey("ticket_nickname")
        val KEY_ISSUED_EPOCH_DAY = longPreferencesKey("ticket_issued_epoch_day")
        val KEY_HALL = intPreferencesKey("ticket_hall")
        val KEY_ROW = intPreferencesKey("ticket_row")
        val KEY_SEAT = intPreferencesKey("ticket_seat")
    }

    /**
     * 已落盘的票根，没取过票时为 null。
     *
     * 五个字段任一缺失一律当没票：残缺的票根渲染出来是一张缺字的票，不如回到未取票态
     * 让用户重新取一次。
     */
    val stub: Flow<TicketStub?> = context.ticketStubDataStore.data.map { prefs ->
        val nickname = prefs[KEY_NICKNAME] ?: return@map null
        val issuedEpochDay = prefs[KEY_ISSUED_EPOCH_DAY] ?: return@map null
        val hall = prefs[KEY_HALL] ?: return@map null
        val row = prefs[KEY_ROW] ?: return@map null
        val seat = prefs[KEY_SEAT] ?: return@map null
        TicketStub(
            nickname = nickname,
            issuedEpochDay = issuedEpochDay,
            hall = hall,
            row = row,
            seat = seat,
        )
    }.distinctUntilChanged()

    suspend fun save(stub: TicketStub) {
        context.ticketStubDataStore.edit { prefs ->
            prefs[KEY_NICKNAME] = stub.nickname
            prefs[KEY_ISSUED_EPOCH_DAY] = stub.issuedEpochDay
            prefs[KEY_HALL] = stub.hall
            prefs[KEY_ROW] = stub.row
            prefs[KEY_SEAT] = stub.seat
        }
    }

    /** 取票码失效后回到未取票态：整份清掉，别留下半张票。 */
    suspend fun clear() {
        context.ticketStubDataStore.edit { it.clear() }
    }
}
