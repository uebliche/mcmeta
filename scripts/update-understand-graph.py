#!/usr/bin/env python3
"""Deterministically refresh .understand-anything for this repository.

This intentionally uses the Understand Anything plugin helper scripts for scan,
import-map extraction, batching, structure extraction, and fingerprints, then
assembles a stable local knowledge graph without LLM calls. It is designed for
GitHub Actions push-refresh jobs.
"""
import datetime
import json
import os
import pathlib
import re
import subprocess
import sys
from collections import Counter

PROJECT = pathlib.Path(os.environ.get("GITHUB_WORKSPACE", os.getcwd())).resolve()
PLUGIN = pathlib.Path(os.environ.get("UNDERSTAND_PLUGIN_ROOT", os.path.expanduser("~/.understand-anything/repo/understand-anything-plugin"))).resolve()
SKILL = PLUGIN / "skills" / "understand"


def run(cmd, check=True):
    print("+", " ".join(map(str, cmd)), flush=True)
    p = subprocess.run(cmd, cwd=PROJECT, text=True, capture_output=True)
    if p.stdout:
        print(p.stdout, end="")
    if p.stderr:
        print(p.stderr, end="", file=sys.stderr)
    if check and p.returncode != 0:
        raise RuntimeError(f"command failed ({p.returncode}): {cmd}")
    return p


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def slug(value):
    value = re.sub(r"[^A-Za-z0-9_.:-]+", "-", value or "").strip("-")
    return value[:120] or "unnamed"


def node_type(file_meta):
    category = file_meta.get("fileCategory")
    path = file_meta.get("path", "")
    if category == "docs":
        return "document"
    if category == "infra":
        return "pipeline" if path.startswith(".github/workflows/") else "service"
    if category == "config":
        return "config"
    if category == "data":
        return "schema" if path.endswith((".json", ".toml", ".yaml", ".yml")) else "resource"
    return "file"


def complexity(lines):
    if lines >= 400:
        return "complex"
    if lines >= 160:
        return "moderate"
    return "simple"


def file_summary(project_name, file_meta, structure=None):
    path = file_meta["path"]
    category = file_meta.get("fileCategory", "file")
    language = file_meta.get("language", "text")
    lines = file_meta.get("sizeLines", 0)
    details = []
    if structure:
        for key, label in [
            ("functions", "functions"),
            ("classes", "classes/types"),
            ("exports", "exports"),
            ("sections", "sections"),
            ("services", "services"),
            ("endpoints", "endpoints"),
        ]:
            if structure.get(key):
                details.append(f"{len(structure[key])} {label}")
    suffix = (", " + ", ".join(details)) if details else ""
    return f"{path} is a {category} file ({language}, {lines} lines{suffix}) in {project_name}."


def build_layers(file_node_by_path):
    definitions = [
        ("layer:apps", "Apps", "Application entrypoints and app-specific source.", lambda p: p.startswith(("apps/", "app/", "src-tauri/"))),
        ("layer:crates", "Crates", "Rust crates and shared Rust workspace modules.", lambda p: p.startswith(("crates/", "src/"))),
        ("layer:packages", "Packages", "Node/package workspace modules and frontend packages.", lambda p: p.startswith(("packages/", "ui/", "frontend/"))),
        ("layer:shared", "Shared", "Shared contracts, schemas, libraries, and reusable assets.", lambda p: p.startswith(("shared/", "libs/", "lib/"))),
        ("layer:scripts", "Scripts", "Automation, release, build and maintenance scripts.", lambda p: p.startswith(("scripts/", "tools/"))),
        ("layer:ci-config", "CI Config", "GitHub Actions and project configuration.", lambda p: p.startswith((".github/", "config/", "deploy/")) or "/" not in p),
        ("layer:docs", "Docs", "Documentation and guides.", lambda p: p.startswith("docs/") or p.lower().endswith(".md")),
    ]
    assigned = set()
    layers = []
    for layer_id, name, description, predicate in definitions:
        node_ids = []
        for path, node_id in file_node_by_path.items():
            if node_id not in assigned and predicate(path):
                node_ids.append(node_id)
                assigned.add(node_id)
        if node_ids:
            layers.append({"id": layer_id, "name": name, "description": description, "nodeIds": node_ids})
    remainder = [node_id for node_id in file_node_by_path.values() if node_id not in assigned]
    if remainder:
        layers.append({"id": "layer:other", "name": "Other", "description": "Remaining project files not covered by primary layers.", "nodeIds": remainder})
    return layers


def main():
    if not (SKILL / "scan-project.mjs").exists():
        raise RuntimeError(f"Understand plugin helpers not found under {SKILL}")

    commit = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    ua = PROJECT / ".understand-anything"
    intermediate = ua / "intermediate"
    tmp = ua / "tmp"
    intermediate.mkdir(parents=True, exist_ok=True)
    tmp.mkdir(parents=True, exist_ok=True)
    write_json(ua / "config.json", {"outputLanguage": "de", "autoUpdate": False})

    scan_raw = intermediate / "scan-raw.json"
    run(["node", str(SKILL / "scan-project.mjs"), str(PROJECT), str(scan_raw)])
    scan = read_json(scan_raw)

    import_input = tmp / "import-input.json"
    import_output = intermediate / "import-map.json"
    write_json(import_input, {"projectRoot": str(PROJECT), "files": scan["files"]})
    run(["node", str(SKILL / "extract-import-map.mjs"), str(import_input), str(import_output)])
    import_map = read_json(import_output).get("importMap", {})

    package = read_json(PROJECT / "package.json") if (PROJECT / "package.json").exists() else {}
    project_name = package.get("name") or PROJECT.name
    frameworks = ["Git"]
    if (PROJECT / "Cargo.toml").exists():
        frameworks.append("Cargo")
    if (PROJECT / "package.json").exists():
        frameworks.append("Node.js")

    scan_result = {
        "projectName": project_name,
        "projectDescription": f"{PROJECT.name} project in the Uebliche workspace.",
        "languages": sorted(scan.get("stats", {}).get("byLanguage", {}).keys()),
        "frameworks": frameworks,
        "files": scan["files"],
        "totalFiles": scan["totalFiles"],
        "filteredByIgnore": scan.get("filteredByIgnore", 0),
        "estimatedComplexity": scan.get("estimatedComplexity"),
        "stats": scan.get("stats", {}),
        "importMap": import_map,
    }
    write_json(intermediate / "scan-result.json", scan_result)

    run(["node", str(SKILL / "compute-batches.mjs"), str(PROJECT)])
    batches = read_json(intermediate / "batches.json").get("batches", [])
    structures = {}
    for batch in batches:
        index = batch.get("batchIndex")
        files = batch.get("files", [])
        extract_input = tmp / f"extract-{index}.json"
        extract_output = intermediate / f"structure-{index}.json"
        batch_import_map = {f["path"]: import_map.get(f["path"], []) for f in files}
        write_json(extract_input, {"projectRoot": str(PROJECT), "batchFiles": files, "batchImportData": batch_import_map})
        run(["node", str(SKILL / "extract-structure.mjs"), str(extract_input), str(extract_output)])
        for result in read_json(extract_output).get("results", []):
            structures[result["path"]] = result

    nodes = []
    edges = []
    node_ids = set()
    file_node_by_path = {}

    def add_node(node):
        if node.get("id") and node["id"] not in node_ids:
            node_ids.add(node["id"])
            nodes.append(node)

    def add_edge(source, target, edge_type, weight=0.5, summary=None):
        if source in node_ids and target in node_ids:
            edge = {"source": source, "target": target, "type": edge_type, "weight": weight}
            if summary:
                edge["summary"] = summary
            edges.append(edge)

    for file_meta in scan["files"]:
        path = file_meta["path"]
        structure = structures.get(path)
        kind = node_type(file_meta)
        node_id = f"{kind}:{path}"
        file_node_by_path[path] = node_id
        tags = [file_meta.get("fileCategory", "file"), file_meta.get("language", "text"), path.split("/")[0] if "/" in path else "root"]
        add_node({
            "id": node_id,
            "type": kind,
            "name": pathlib.PurePosixPath(path).name,
            "filePath": path,
            "summary": file_summary(project_name, file_meta, structure),
            "tags": tags,
            "complexity": complexity(file_meta.get("sizeLines", 0)),
        })
        if not structure:
            continue
        for fn in (structure.get("functions") or [])[:300]:
            fn_id = f"function:{path}:{slug(fn.get('name'))}"
            add_node({"id": fn_id, "type": "function", "name": fn.get("name", "function"), "filePath": path, "summary": f"Function {fn.get('name', 'function')} in {path}.", "tags": ["function", file_meta.get("language", "code")], "complexity": "simple"})
            add_edge(node_id, fn_id, "contains", 1.0)
        for cls in (structure.get("classes") or [])[:300]:
            cls_id = f"class:{path}:{slug(cls.get('name'))}"
            add_node({"id": cls_id, "type": "class", "name": cls.get("name", "class"), "filePath": path, "summary": f"Type/class {cls.get('name', 'class')} in {path}.", "tags": ["class", file_meta.get("language", "code")], "complexity": "simple"})
            add_edge(node_id, cls_id, "contains", 1.0)
        for endpoint in (structure.get("endpoints") or [])[:150]:
            endpoint_id = f"endpoint:{path}:{slug(endpoint.get('method', '') + '-' + endpoint.get('path', 'endpoint'))}"
            add_node({"id": endpoint_id, "type": "endpoint", "name": f"{endpoint.get('method', '')} {endpoint.get('path', '')}".strip(), "filePath": path, "summary": f"Endpoint in {path}.", "tags": ["endpoint"], "complexity": "simple"})
            add_edge(node_id, endpoint_id, "contains", 1.0)

    for source_path, targets in import_map.items():
        source_id = file_node_by_path.get(source_path)
        for target_path in targets:
            target_id = file_node_by_path.get(target_path)
            if source_id and target_id:
                add_edge(source_id, target_id, "imports", 0.7, f"{source_path} imports {target_path}.")

    for path, structure in structures.items():
        for call in (structure.get("callGraph") or [])[:700]:
            caller = f"function:{path}:{slug(call.get('caller'))}"
            callee = f"function:{path}:{slug(call.get('callee'))}"
            if caller in node_ids and callee in node_ids and caller != callee:
                add_edge(caller, callee, "calls", 0.8)

    seen_edges = set()
    deduped_edges = []
    for edge in edges:
        key = (edge["source"], edge["target"], edge["type"])
        if key not in seen_edges:
            seen_edges.add(key)
            deduped_edges.append(edge)
    edges = deduped_edges

    layers = build_layers(file_node_by_path)
    tour = []
    for title, description, prefixes in [
        ("Project overview", "Start with root manifests and README files.", ["README.md", "package.json", "Cargo.toml"]),
        ("Application sources", "Inspect key application and source entrypoints.", ["src/", "apps/", "app/", "crates/"]),
        ("Shared/package code", "Review shared packages, crates and contracts.", ["packages/", "shared/", "libs/", "lib/"]),
        ("Automation", "Follow build, CI and maintenance scripts.", ["scripts/", "tools/", ".github/workflows/"]),
    ]:
        ids = []
        for path, node_id in file_node_by_path.items():
            if any(path == prefix or path.startswith(prefix) for prefix in prefixes):
                ids.append(node_id)
            if len(ids) >= 8:
                break
        if ids:
            tour.append({"order": len(tour) + 1, "title": title, "description": description, "nodeIds": ids})

    graph = {
        "version": "1.0.0",
        "project": {
            "name": project_name,
            "languages": scan_result["languages"],
            "frameworks": frameworks,
            "description": scan_result["projectDescription"],
            "analyzedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "gitCommitHash": commit,
        },
        "nodes": nodes,
        "edges": edges,
        "layers": layers,
        "tour": tour,
    }

    ids = {node["id"] for node in nodes}
    dangling = [edge for edge in edges if edge["source"] not in ids or edge["target"] not in ids]
    if dangling:
        raise RuntimeError(f"internal validation failed: {len(dangling)} dangling edges")

    write_json(intermediate / "assembled-graph.json", graph)
    write_json(ua / "knowledge-graph.json", graph)

    fingerprint_input = intermediate / "fingerprint-input.json"
    write_json(fingerprint_input, {"projectRoot": str(PROJECT), "sourceFilePaths": [f["path"] for f in scan["files"]], "gitCommitHash": commit})
    fingerprint = run(["node", str(SKILL / "build-fingerprints.mjs"), str(fingerprint_input)])
    if "Fingerprints baseline:" not in fingerprint.stdout:
        raise RuntimeError("fingerprint baseline generation did not report success")

    meta = {
        "lastAnalyzedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "gitCommitHash": commit,
        "version": "1.0.0",
        "analyzedFiles": len(scan["files"]),
        "mode": "github-action-deterministic",
    }
    write_json(ua / "meta.json", meta)

    review = {
        "issues": [],
        "warnings": [],
        "stats": {
            "totalNodes": len(nodes),
            "totalEdges": len(edges),
            "totalLayers": len(layers),
            "tourSteps": len(tour),
            "nodeTypes": dict(Counter(node["type"] for node in nodes)),
            "edgeTypes": dict(Counter(edge["type"] for edge in edges)),
            "filesAnalyzed": len(scan["files"]),
            "byCategory": scan.get("stats", {}).get("byCategory", {}),
        },
    }
    write_json(intermediate / "review.json", review)
    print("SUMMARY " + json.dumps(review["stats"], ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
