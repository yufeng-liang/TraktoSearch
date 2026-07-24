UPDATE audit_logs
SET detail = (
    SELECT 'invite_kind:' || i.kind || ';invite_id:' || i.id || ';invite_mask:' || COALESCE(i.code_mask, '')
    FROM invites i
    WHERE i.friend_id = audit_logs.friend_id
      AND i.created_at = audit_logs.created_at
    ORDER BY i.created_at DESC, i.id DESC
    LIMIT 1
)
WHERE event_type = 'INVITE_CREATE'
  AND (detail IS NULL OR instr(detail, 'invite_id:') = 0)
  AND EXISTS (
      SELECT 1
      FROM invites i
      WHERE i.friend_id = audit_logs.friend_id
        AND i.created_at = audit_logs.created_at
  );
