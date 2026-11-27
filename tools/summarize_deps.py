# -*- coding: utf-8 -*-
import json
d = json.load(open(".cache_deps.json", encoding="utf-8"))
rows = d["rows"]
upd = [x for x in rows if x.get("status") == "update"]
err = [x for x in rows if "error" in x]
ok = [x for x in rows if x.get("status") == "current"]
print("TOTAL", len(rows), "UPDATE", len(upd), "OK", len(ok), "ERR", len(err))
print("\n=== 可更新 ===")
for x in sorted(upd, key=lambda r: r["alias"]):
    print(f'{x["alias"]:42s} {x["current"]:12s} -> {x["latestStable"]:12s}  overall={x.get("latestOverall")}  [{x["repo"]}]')
print("\n=== 已最新 ===")
for x in sorted(ok, key=lambda r: r["alias"]):
    print(f'{x["alias"]:42s} {x["current"]:12s} latest={x.get("latestStable")}')
print("\n=== 错误 ===")
for x in err:
    print(x["alias"], x["current"], x["error"][:80])
print("\n=== BOM 管理（不单独查）===")
print(", ".join(d["bomManaged"]))
