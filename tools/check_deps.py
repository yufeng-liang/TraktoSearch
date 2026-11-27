# -*- coding: utf-8 -*-
import json, re, urllib.request, concurrent.futures

GOOGLE = "https://dl.google.com/dl/android/maven2/{path}/group-index.xml"
CENTRAL = "https://repo1.maven.org/maven2/{path}/maven-metadata.xml"
CAT = "gradle/libs.versions.toml"

def get(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": "dep-check/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")

_gcache = {}
def google_group(group):
    if group in _gcache: return _gcache[group]
    try:
        xml = get(GOOGLE.format(path=group.replace(".", "/")))
    except Exception as e:
        _gcache[group] = {"__error__": str(e)}; return _gcache[group]
    out = {}
    for m in re.finditer(r'<([A-Za-z0-9_.\-]+)\s+versions="([^"]*)"', xml):
        out.setdefault(m.group(1), []).extend([v for v in m.group(2).split(",") if v])
    _gcache[group] = out; return out

_ccache = {}
def central_versions(group, artifact):
    key = (group, artifact)
    if key in _ccache: return _ccache[key]
    try:
        xml = get(CENTRAL.format(path=group.replace(".", "/") + "/" + artifact))
        vers = re.findall(r"<version>([^<]+)</version>", xml)
    except Exception as e:
        vers = {"__error__": str(e)}
    _ccache[key] = vers; return vers

def versions_for(repo, group, artifact):
    if isinstance(repo, str) and repo == "google":
        g = google_group(group)
        if "__error__" in g: return {"__error__": g["__error__"]}
        return g.get(artifact, [])
    return central_versions(group, artifact)

def vkey(v):
    parts = re.split(r"[.\-_+]", v)
    key = []
    for p in parts:
        if p.isdigit(): key.append((0, int(p), ""))
        else: key.append((1, 0, p.lower()))
    return key

PRERE = re.compile(r"(?i)(alpha|beta|rc|dev|snapshot|eap|preview|pre\d|canary|nightly|-m\d+$)")
def is_stable(v):
    return not PRERE.search(v)

def pick(versions, stable_only):
    cand = [v for v in versions if isinstance(v, str) and not v.startswith("__")]
    if stable_only: cand = [v for v in cand if is_stable(v)]
    return max(cand, key=vkey) if cand else None

def parse_catalog():
    text = open(CAT, encoding="utf-8").read()
    versions, libs = {}, []
    section = None
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip() if not raw.strip().startswith("#") else ""
        if not line: continue
        if line.startswith("["):
            section = line.strip("[]").strip(); continue
        if section == "versions":
            m = re.match(r'^([A-Za-z0-9_.\-]+)\s*=\s*"([^"]*)"', line)
            if m: versions[m.group(1)] = m.group(2)
        elif section == "libraries":
            m = re.match(r'^([A-Za-z0-9_.\-]+)\s*=\s*\{(.*)\}\s*$', line)
            if not m: continue
            alias, body = m.group(1), m.group(2)
            gm = re.search(r'group\s*=\s*"([^"]+)"', body)
            nm = re.search(r'name\s*=\s*"([^"]+)"', body)
            md = re.search(r'module\s*=\s*"([^"]+):([^"]+)"', body)
            vr = re.search(r'version\.ref\s*=\s*"([^"]+)"', body)
            if md: group, artifact = md.group(1), md.group(2)
            elif gm and nm: group, artifact = gm.group(1), nm.group(1)
            else: continue
            libs.append({"alias": alias, "group": group, "artifact": artifact,
                         "version": versions.get(vr.group(1)) if vr else None,
                         "ref": vr.group(1) if vr else None})
    return versions, libs

def repo_of(group):
    if group.startswith("androidx") or group.startswith("com.android"):
        return "google"
    return "central"

def main():
    versions, libs = parse_catalog()
    rows, bom_managed = [], []
    tasks = []
    for lib in libs:
        if not lib["version"]:
            bom_managed.append(lib["alias"] + "  (" + lib["group"] + ":" + lib["artifact"] + ")")
            continue
        tasks.append(lib)
    # 插件类补充
    extra = [
        {"alias": "plugin:AGP", "group": "com.android.tools.build", "artifact": "gradle", "version": versions.get("agp")},
        {"alias": "plugin:kotlin-compose", "group": "org.jetbrains.kotlin", "artifact": "compose-compiler-gradle-plugin", "version": versions.get("kotlin")},
        {"alias": "plugin:kotlin-serialization", "group": "org.jetbrains.kotlin", "artifact": "kotlin-serialization", "version": versions.get("kotlin")},
        {"alias": "plugin:hilt", "group": "com.google.dagger", "artifact": "hilt-android-gradle-plugin", "version": versions.get("hilt")},
        {"alias": "plugin:ksp", "group": "com.google.devtools.ksp", "artifact": "symbol-processing-gradle-plugin", "version": versions.get("ksp")},
        {"alias": "plugin:baselineprofile", "group": "androidx.baselineprofile", "artifact": "baselineprofile", "version": versions.get("benchmark")},
    ]
    tasks.extend(extra)

    def work(lib):
        repo = repo_of(lib["group"])
        vs = versions_for(repo, lib["group"], lib["artifact"])
        if isinstance(vs, dict):
            return {"alias": lib["alias"], "coord": lib["group"] + ":" + lib["artifact"],
                    "current": lib["version"], "error": vs["__error__"], "repo": repo}
        ls = pick(vs, True); la = pick(vs, False)
        cur = lib["version"]
        status = "?"
        if ls:
            status = "update" if vkey(ls) > vkey(cur) else "current"
        return {"alias": lib["alias"], "coord": lib["group"] + ":" + lib["artifact"],
                "current": cur, "latestStable": ls, "latestOverall": la, "repo": repo,
                "status": status, "n": len(vs)}

    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as ex:
        rows = list(ex.map(work, tasks))
    print(json.dumps({"rows": rows, "bomManaged": bom_managed}, ensure_ascii=False, indent=1))

main()
