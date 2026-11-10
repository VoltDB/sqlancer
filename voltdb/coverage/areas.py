"""Rolls run-coverage.sh's per-package (Java) and per-directory (C++) coverage up into the areas of VoltDB that
ENG-30396 asks about, and prints one line per area.

Usage: python3 areas.py REPORT_DIR   (reads java/classes.csv and, if present, ee/dirs.tsv)

A Java prefix is a package (all its classes and subpackages) or a package and class name prefix
(org.voltdb.CommandLog: every class in org.voltdb whose name starts with CommandLog, nested classes included).
"""
import os
import sys

# area: (Java package prefixes, execution engine directories under src/ee/)
AREAS = [
    ("SQL parser and planner", ["org.voltdb.planner", "org.voltdb.plannodes", "org.voltdb.compiler",
                                "org.voltdb.parser", "org.hsqldb_voltpatches"], []),
    ("executors", [], ["executors"]),
    ("expressions and functions", ["org.voltdb.expressions"], ["expressions"]),
    ("storage and indexes", [], ["storage", "indexes"]),
    ("plan nodes (EE)", [], ["plannodes"]),
    ("DR and XDCR", ["org.voltdb.dr2", "org.voltdb.dr"], []),
    ("export and streams", ["org.voltdb.export", "org.voltdb.exportclient"], []),
    ("rejoin", ["org.voltdb.rejoin"], []),
    ("elastic resize", ["org.voltdb.elastic"], []),
    ("command log and recovery", ["org.voltdb.CommandLog", "org.voltdb.RestoreAgent", "org.voltdb.snapshot",
                                  "org.voltdb.sysprocs.saverestore"], []),
    ("system procedures", ["org.voltdb.sysprocs"], []),
    ("security", ["org.voltdb.auth", "org.voltdb.security"], []),
]


def read(path):
    rows = {}
    if not os.path.exists(path):
        return rows
    with open(path) as f:
        next(f)
        for line in f:
            p = line.rstrip("\n").split("\t")
            rows[p[0]] = [int(x) for x in p[1:5]]
    return rows


def read_classes(path):
    """JaCoCo's CSV: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,
    LINE_MISSED,LINE_COVERED,... -> {package.Class: [lines covered, lines, branches covered, branches]}"""
    import csv
    rows = {}
    with open(path) as f:
        for r in csv.DictReader(f):
            lm, lc = int(r["LINE_MISSED"]), int(r["LINE_COVERED"])
            bm, bc = int(r["BRANCH_MISSED"]), int(r["BRANCH_COVERED"])
            rows[r["PACKAGE"] + "." + r["CLASS"]] = [lc, lc + lm, bc, bc + bm]
    return rows


def matches(name, prefixes):
    pkg = name.rsplit(".", 1)[0]
    for p in prefixes:
        if pkg == p or pkg.startswith(p + "."):
            return True
        if p.rsplit(".", 1)[-1][:1].isupper() and name.startswith(p):
            return True
    return False


def main():
    report = sys.argv[1]
    java = read_classes(os.path.join(report, "java", "classes.csv"))
    ee = read(os.path.join(report, "ee", "dirs.tsv"))
    print("area\tlines\tbranches")
    for name, pkgs, dirs in AREAS:
        t = [0, 0, 0, 0]
        for k, v in java.items():
            if matches(k, pkgs):
                t = [a + b for a, b in zip(t, v)]
        for k, v in ee.items():
            if k in dirs:
                t = [a + b for a, b in zip(t, v)]
        if t[1] == 0:
            continue
        print("%s\t%d of %d (%.1f%%)\t%d of %d (%.1f%%)"
              % (name, t[0], t[1], 100.0 * t[0] / t[1], t[2], t[3], 100.0 * t[2] / max(t[3], 1)))


if __name__ == "__main__":
    main()
