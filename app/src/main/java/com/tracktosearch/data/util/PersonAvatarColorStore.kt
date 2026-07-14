package com.tracktosearch.data.util

/**
 * 进程内 personId -> 头像主色（ARGB）缓存。
 *
 * 前一屏 CastCard 加载头像并提取主色后写入，PersonScreen 首帧读取，
 * 避免首次进入演职员详情页时背景从白色闪烁到沉浸色。
 * 路由参数无法传递 Color 对象，用此全局缓存桥接。
 */
object PersonAvatarColorStore {
    private val cache = android.util.LruCache<Int, Long>(50)

    fun put(personId: Int, argb: Long) {
        if (personId > 0 && argb != 0L) cache.put(personId, argb)
    }

    fun get(personId: Int): Long? = cache.get(personId)?.takeIf { it != 0L }
}
