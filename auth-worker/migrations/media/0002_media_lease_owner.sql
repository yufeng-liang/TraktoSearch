-- 刷新租约增加所有者，避免租约过期重抢后旧任务误删新租约。
ALTER TABLE media_refresh_lease ADD COLUMN owner TEXT NOT NULL DEFAULT '';
