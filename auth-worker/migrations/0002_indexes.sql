// 0002_indexes.sql — 索引优化

-- 朋友表索引
CREATE INDEX IF NOT EXISTS idx_friends_status ON friends(status);
CREATE INDEX IF NOT EXISTS idx_friends_expires_at ON friends(expires_at);

-- 邀请码表索引
CREATE INDEX IF NOT EXISTS idx_invites_friend_id ON invites(friend_id);
CREATE INDEX IF NOT EXISTS idx_invites_code_hash ON invites(code_hash);

-- 设备表索引
CREATE INDEX IF NOT EXISTS idx_devices_friend_id_status ON devices(friend_id, status);
CREATE INDEX IF NOT EXISTS idx_devices_public_key ON devices(public_key);

-- 刷新会话索引
CREATE INDEX IF NOT EXISTS idx_refresh_sessions_device_id ON refresh_sessions(device_id);
CREATE INDEX IF NOT EXISTS idx_refresh_sessions_token_hash ON refresh_sessions(token_hash);

-- 审计日志索引
CREATE INDEX IF NOT EXISTS idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_logs_friend_id ON audit_logs(friend_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs(created_at);
