"""Inventory public Maven dependencies and query exact resolved versions in OSV."""
import json
import pathlib
import urllib.request
import urllib.error
import time
from collections import defaultdict

out = pathlib.Path("auditoria-etapa9")
root = json.loads((out / "dependency-tree.json").read_text(encoding="utf-8"))
coords = defaultdict(lambda: {"scopes": set(), "types": set()})

def visit(node):
    name = node["groupId"] + ":" + node["artifactId"]
    version = node["version"]
    scope = node.get("scope") or "unspecified"
    # The repository's application coordinate is not queried.
    entry = coords[(name, version)]
    entry["scopes"].add(scope)
    entry["types"].add(node.get("type", "jar"))
    for child in node.get("children", []):
        visit(child)

for child in root.get("children", []):
    visit(child)
assert coords, "Empty dependency tree"
inventory = [{"name": n, "version": v, "scopes": sorted(x["scopes"]), "types": sorted(x["types"])}
             for (n, v), x in sorted(coords.items())]
(out / "inventory.json").write_text(json.dumps(inventory, indent=2), encoding="utf-8")

def query(queries):
    data = json.dumps({"queries": queries}).encode()
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=data,
                                 headers={"Content-Type": "application/json", "User-Agent": "SIBRAPS-CI-inventory"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=45) as response:
                obj = json.load(response)
            results = obj["results"]
            assert len(results) == len(queries), "OSV response cardinality mismatch"
            return results
        except (urllib.error.URLError, TimeoutError) as exc:
            if attempt == 2:
                raise
            if isinstance(exc, urllib.error.HTTPError) and exc.code < 500 and exc.code != 429:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("OSV query failed")

matches = []
for start in range(0, len(inventory), 50):
    batch = inventory[start:start+50]
    pending = [(x, {"package": {"ecosystem": "Maven", "name": x["name"]}, "version": x["version"]}) for x in batch]
    found = defaultdict(dict)
    while pending:
        results = query([q for _, q in pending])
        next_pending = []
        for (item, q), result in zip(pending, results):
            key = (item["name"], item["version"])
            for vuln in result.get("vulns", []):
                found[key][vuln["id"]] = vuln
            token = result.get("next_page_token")
            if token:
                assert token != q.get("page_token"), "Repeated OSV page token"
                next_pending.append((item, {**q, "page_token": token}))
        pending = next_pending
    for item in batch:
        vulns = list(found[(item["name"], item["version"])].values())
        if vulns:
            matches.append({**item, "vulnerabilities": vulns})
    (out / "osv-partial.json").write_text(json.dumps({"consultadas": min(start+50, len(inventory)), "matches": matches}, indent=2), encoding="utf-8")
report = {"consultadas": len(inventory), "packagesWithMatches": len(matches), "matches": matches,
          "nota": "Correspondencias de versao OSV, sem conclusao de explorabilidade, severidade ou inclusao no artefato final."}
(out / "osv-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps({"consultadas": len(inventory), "packagesWithMatches": len(matches)}))
for item in matches:
    print("OSV_MATCH " + json.dumps(item, separators=(",", ":")))
