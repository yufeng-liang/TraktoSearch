-- 一次性的运维修复：media-db 曾误应用 auth-worker/migrations 下的认证迁移。
-- 该库只承载公开影视缓存，删除误建认证表后仅保留媒体三表。
PRAGMA foreign_keys = OFF;

DROP TABLE IF EXISTS ai_cache;
DROP TABLE IF EXISTS ai_daily_illustrations;
DROP TABLE IF EXISTS ai_health_events;
DROP TABLE IF EXISTS ai_profile_behavior_daily;
DROP TABLE IF EXISTS ai_profile_feedback;
DROP TABLE IF EXISTS ai_profile_media;
DROP TABLE IF EXISTS ai_profile_media_sources;
DROP TABLE IF EXISTS ai_profile_settings;
DROP TABLE IF EXISTS ai_profile_snapshot;
DROP TABLE IF EXISTS ai_profile_sync_batches;
DROP TABLE IF EXISTS ai_tts_rate_limits;
DROP TABLE IF EXISTS ai_usage;
DROP TABLE IF EXISTS audit_logs;
DROP TABLE IF EXISTS auth_challenges;
DROP TABLE IF EXISTS devices;
DROP TABLE IF EXISTS friend_ip_logs;
DROP TABLE IF EXISTS friends;
DROP TABLE IF EXISTS invite_requests;
DROP TABLE IF EXISTS invites;
DROP TABLE IF EXISTS legal_requests;
DROP TABLE IF EXISTS rate_limits;
DROP TABLE IF EXISTS refresh_idempotency;
DROP TABLE IF EXISTS refresh_sessions;
DROP TABLE IF EXISTS trakt_credentials;

DELETE FROM d1_migrations;
INSERT INTO d1_migrations (name) VALUES ('0001_media_cache.sql');
INSERT INTO d1_migrations (name) VALUES ('0002_media_lease_owner.sql');

PRAGMA foreign_keys = ON;
