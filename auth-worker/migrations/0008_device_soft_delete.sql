ALTER TABLE devices ADD COLUMN deleted_at INTEGER;

CREATE INDEX IF NOT EXISTS idx_devices_friend_visible
    ON devices(friend_id, deleted_at, activated_at DESC);
