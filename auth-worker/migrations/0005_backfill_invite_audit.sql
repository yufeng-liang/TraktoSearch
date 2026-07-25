INSERT INTO audit_logs (
    event_type, friend_id, device_id, request_id, result, detail, created_at
)
SELECT
    CASE WHEN i.kind = 'MIGRATION' THEN 'MIGRATE' ELSE 'ACTIVATE' END,
    i.friend_id,
    NULL,
    NULL,
    'SUCCESS',
    'invite_kind:' || i.kind || ';invite_id:' || i.id || ';invite_mask:' || COALESCE(i.code_mask, ''),
    i.used_at
FROM invites i
WHERE i.used_at IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM audit_logs a
      WHERE a.friend_id = i.friend_id
        AND a.event_type = CASE WHEN i.kind = 'MIGRATION' THEN 'MIGRATE' ELSE 'ACTIVATE' END
        AND a.created_at = i.used_at
  );
