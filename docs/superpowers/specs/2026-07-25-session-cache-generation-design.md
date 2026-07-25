# 会话缓存代次设计

**日期：** 2026-07-25

## 问题

`TtlCache.clear()` 清空内存和飞行中索引后，旧 fetch 仍会在完成时无条件写回。`PersistentTtlCache` 的启动磁盘回填也可在本会话新写入后覆盖新值。Trakt 的用户私有数据因此可能在清理后再次出现。

## 决策

- `TtlCache` 持有单调递增的缓存代次。
- 每次 `clear()` 递增代次；发起 fetch 时捕获当前代次，只有代次未变化时才提交内存结果。原请求的调用者仍可接收其结果，但清理后的新调用者不会加入旧请求。
- `PersistentTtlCache.clearAll()` 继承内存清理语义，并等待 DataStore 中同一前缀删除完成。
- 读盘回填只写入在该次加载开始后未被新写入过的 key，避免旧磁盘值覆盖当前会话写入。
- `TraktRepository` 的用户私有缓存清理改为挂起操作，调用点在既有 `viewModelScope` 内等待完成。

## 非目标

- 不拆分 `TraktRepository`。
- 不改变 TTL、缓存容量、远程接口或 UI 文案。
- 不取消底层网络请求；缓存只保证旧结果不能跨代提交。

## 验证

1. `clear()` 后完成旧 fetch，缓存仍为空。
2. 磁盘回填与新写入竞争时，新写入保留。
3. Trakt 用户缓存清除等待持久化删除。
4. 运行缓存、TraktRepository 相关单测与 debug 编译。
