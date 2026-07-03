SELECT s.name, s.dur / 1e6 as dur_ms, s.ts / 1e9 as ts_s
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
WHERE t.tid = 13377 AND s.dur > 16000000
ORDER BY s.ts;
