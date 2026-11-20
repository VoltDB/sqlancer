"""Summarizes `llvm-cov export -summary-only` JSON for VoltDB's execution engine.

Usage: python3 ee_summary.py EXPORT_JSON OUT_DIR

Writes OUT_DIR/files.tsv (one row per source file, its path from src/ee/) and OUT_DIR/dirs.tsv (one row per
directory under src/ee/), each with covered and total lines and branches, and prints the totals.
"""
import json
import sys
from collections import defaultdict


def rel(path):
    marker = "/src/ee/"
    i = path.find(marker)
    return path[i + len(marker):] if i >= 0 else None


def main():
    data = json.load(open(sys.argv[1]))["data"][0]
    out = sys.argv[2]
    dirs = defaultdict(lambda: [0, 0, 0, 0, 0, 0])
    rows = []
    for f in data["files"]:
        name = rel(f["filename"])
        if name is None:
            continue
        s = f["summary"]
        lc, lt = s["lines"]["covered"], s["lines"]["count"]
        bc, bt = s["branches"]["covered"], s["branches"]["count"]
        rows.append((name, lc, lt, bc, bt))
        d = dirs[name.split("/")[0] if "/" in name else "."]
        d[0] += lc
        d[1] += lt
        d[2] += bc
        d[3] += bt
        d[4] += 1 if lc else 0
        d[5] += 1
    with open(out + "/files.tsv", "w") as w:
        w.write("file\tlines_covered\tlines\tbranches_covered\tbranches\n")
        for r in sorted(rows):
            w.write("\t".join(map(str, r)) + "\n")
    with open(out + "/dirs.tsv", "w") as w:
        w.write("dir\tlines_covered\tlines\tbranches_covered\tbranches\tfiles_run\tfiles\n")
        for k in sorted(dirs):
            w.write(k + "\t" + "\t".join(map(str, dirs[k])) + "\n")
    lc = sum(r[1] for r in rows)
    lt = sum(r[2] for r in rows)
    bc = sum(r[3] for r in rows)
    bt = sum(r[4] for r in rows)
    print("ee: lines %d of %d (%.1f%%), branches %d of %d (%.1f%%), %d files"
          % (lc, lt, 100.0 * lc / max(lt, 1), bc, bt, 100.0 * bc / max(bt, 1), len(rows)))


if __name__ == "__main__":
    main()
