"""Enrich previously collected OSV IDs; no repeat of the baseline tree scan."""
import concurrent.futures
import json
import pathlib
import time
import urllib.request
import urllib.error
import urllib.parse

out = pathlib.Path("auditoria-etapa9")
out.mkdir(exist_ok=True)
base = json.loads(pathlib.Path("../../.github/scripts/etapa9-maven-baseline.json").read_text())
ids = sorted({v["id"] for item in base["matches"] for v in item["vulnerabilities"]})

def fetch(vid):
    url = "https://api.osv.dev/v1/vulns/" + urllib.parse.quote(vid, safe="")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SIBRAPS-CI-inventory"}), timeout=30) as res:
                data = json.load(res)
            assert data["id"] == vid
            return data
        except (urllib.error.URLError, TimeoutError) as exc:
            if attempt == 2:
                raise
            if isinstance(exc, urllib.error.HTTPError) and exc.code < 500 and exc.code != 429:
                raise
            time.sleep(2 ** attempt)

details = {}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    futures = {pool.submit(fetch, vid): vid for vid in ids}
    for future in concurrent.futures.as_completed(futures):
        vid = futures[future]
        details[vid] = future.result()
        (out / "osv-details-partial.json").write_text(json.dumps(details, indent=2))
assert len(details) == len(ids)
(out / "osv-details.json").write_text(json.dumps(details, indent=2))
report = []
for item in base["matches"]:
    avisos = []
    for v in item["vulnerabilities"]:
        data = details[v["id"]]
        affected = [a for a in data.get("affected", []) if a.get("package", {}).get("ecosystem") == "Maven" and a.get("package", {}).get("name") == item["name"]]
        avisos.append({"id": v["id"], "aliases": data.get("aliases", []), "summary": data.get("summary"),
                       "withdrawn": data.get("withdrawn"), "severity": data.get("severity", []),
                       "database_specific": data.get("database_specific", {}),
                       "affected": affected, "references": data.get("references", [])})
    report.append({**item, "avisos": avisos})
(out / "triagem-maven.json").write_text(json.dumps(report, indent=2))
print(json.dumps({"pacotes": len(report), "avisosUnicos": len(ids)}))
for item in report:
    print("OSV_TRIAGEM " + json.dumps({"name": item["name"], "version": item["version"], "avisos": [
        {"id": a["id"], "summary": a["summary"], "withdrawn": a["withdrawn"], "severity": a["severity"],
         "severityLabel": a["database_specific"].get("severity"), "ranges": [r for aff in a["affected"] for r in aff.get("ranges", [])]}
        for a in item["avisos"]]}, separators=(",", ":")))
