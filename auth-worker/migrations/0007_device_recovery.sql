ALTER TABLE devices ADD COLUMN recovery_id_hmac TEXT;
ALTER TABLE devices ADD COLUMN recovery_id_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE devices ADD COLUMN recovery_updated_at INTEGER;
ALTER TABLE invites ADD COLUMN device_id TEXT REFERENCES devices(id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_recovery_id_hmac
    ON devices(recovery_id_hmac)
    WHERE recovery_id_hmac IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_invites_device_id ON invites(device_id);
