"""Compares two run-coverage.sh reports, A and B, using a third report of the two combined (A and B's data merged).

Usage: python3 compare.py REPORT_A REPORT_B REPORT_COMBINED

Prints, for Java and for the execution engine: the lines each covers; how many lines only A covers (combined minus
B) and only B covers (combined minus A); and the packages (Java) and directories and files (C++) where each adds
the most lines the other does not reach. Line counts are exact: the combined report counts a line once, whichever
side ran it.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from areas import AREAS, matches, read, read_classes  # noqa: E402


def java_by(report, key):
    out = {}
    for name, v in read_classes(os.path.join(report, "java", "classes.csv")).items():
        k = key(name)
        t = out.setdefault(k, [0, 0, 0, 0])
        for i in range(4):
            t[i] += v[i]
    return out


def package(name):
    return name.rsplit(".", 1)[0]


def ee_files(report):
    return read(os.path.join(report, "ee", "files.tsv"))


def total(rows):
    return [sum(v[i] for v in rows.values()) for i in range(4)]


def pct(c, t):
    return "%.1f%%" % (100.0 * c / t) if t else "-"


def gains(a, b, combined, n=15):
    """keys ranked by the lines covered in the combined report but not in b: what a adds over b"""
    g = []
    for k, c in combined.items():
        extra = c[0] - b.get(k, [0])[0]
        if extra > 0:
            g.append((extra, k, c[1]))
    return sorted(g, reverse=True)[:n]


def section(title, a, b, c, names=("A", "B")):
    ta, tb, tc = total(a), total(b), total(c)
    if tc[1] == 0:
        return
    print("== %s: %d lines" % (title, tc[1]))
    print("  %s covers %d (%s), %s covers %d (%s), together %d (%s)"
          % (names[0], ta[0], pct(ta[0], ta[1]), names[1], tb[0], pct(tb[0], tb[1]), tc[0], pct(tc[0], tc[1])))
    print("  only %s: %d lines; only %s: %d lines" % (names[0], tc[0] - tb[0], names[1], tc[0] - ta[0]))
    for who, x, y in ((names[0], a, b), (names[1], b, a)):
        print("  most lines only %s covers:" % who)
        for extra, k, lines in gains(x, y, c):
            print("    %6d  %s (of %d lines)" % (extra, k, lines))


def areas(ra, rb, rc, names):
    print("== areas: lines covered, %s / %s / together" % names)
    ja, jb, jc = (read_classes(os.path.join(r, "java", "classes.csv")) for r in (ra, rb, rc))
    ea, eb, ec = (read(os.path.join(r, "ee", "dirs.tsv")) for r in (ra, rb, rc))
    for name, pkgs, dirs in AREAS:
        row = []
        for j, e in ((ja, ea), (jb, eb), (jc, ec)):
            t = [0, 0]
            for k, v in j.items():
                if matches(k, pkgs):
                    t[0] += v[0]
                    t[1] += v[1]
            for k, v in e.items():
                if k in dirs:
                    t[0] += v[0]
                    t[1] += v[1]
            row.append(t)
        if row[2][1]:
            print("  %-28s %s / %s / %s of %d" % (name, pct(*row[0]), pct(*row[1]), pct(*row[2]), row[2][1]))


def main():
    ra, rb, rc = sys.argv[1:4]
    names = (os.environ.get("NAME_A", "A"), os.environ.get("NAME_B", "B"))
    print("A = %s\nB = %s\n" % (os.path.dirname(os.path.abspath(ra)), os.path.dirname(os.path.abspath(rb))))
    areas(ra, rb, rc, names)
    section("Java, by package", java_by(ra, package), java_by(rb, package), java_by(rc, package), names)
    fa, fb, fc = ee_files(ra), ee_files(rb), ee_files(rc)

    def dirs(f):
        out = {}
        for k, v in f.items():
            d = k.split("/")[0] if "/" in k else "."
            t = out.setdefault(d, [0, 0, 0, 0])
            for i in range(4):
                t[i] += v[i]
        return out

    section("execution engine, by directory", dirs(fa), dirs(fb), dirs(fc), names)
    section("execution engine, by file", fa, fb, fc, names)


if __name__ == "__main__":
    main()
