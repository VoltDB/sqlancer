import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.MultiSourceFileLocator;
import org.jacoco.report.csv.CSVFormatter;
import org.jacoco.report.html.HTMLFormatter;

/**
 * Turns the JaCoCo execution data that run-coverage.sh collects from VoltDB's server JVMs into an HTML report, a
 * per-class CSV, and a line and branch summary per package (packages.tsv), for the classes in the given jars.
 *
 * <p>
 * Usage: java CoverageReport OUT_DIR EXEC_FILE[,EXEC_FILE...] JAR[,JAR...] [SOURCE_DIR[,SOURCE_DIR...]]
 *
 * <p>
 * Only classes whose execution data matches the jar's class bytes count; data from another build of a class (a
 * different kit's JVM writing to the same file) is ignored.
 */
public final class CoverageReport {

    private CoverageReport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: CoverageReport OUT_DIR EXEC[,EXEC...] JAR[,JAR...] [SRC_DIR[,SRC_DIR...]]");
            System.exit(2);
        }
        File out = new File(args[0]);
        out.mkdirs();
        ExecFileLoader loader = new ExecFileLoader();
        for (String exec : args[1].split(",")) {
            loader.load(new File(exec));
        }
        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        for (String jar : args[2].split(",")) {
            analyzer.analyzeAll(new File(jar));
        }
        IBundleCoverage bundle = builder.getBundle("VoltDB");

        MultiSourceFileLocator sources = new MultiSourceFileLocator(4);
        if (args.length > 3) {
            for (String dir : args[3].split(",")) {
                sources.add(new DirectorySourceFileLocator(new File(dir), "UTF-8", 4));
            }
        }
        IReportVisitor html = new HTMLFormatter().createVisitor(new FileMultiReportOutput(new File(out, "html")));
        html.visitInfo(loader.getSessionInfoStore().getInfos(), loader.getExecutionDataStore().getContents());
        html.visitBundle(bundle, sources);
        html.visitEnd();
        try (OutputStream csv = new FileOutputStream(new File(out, "classes.csv"))) {
            IReportVisitor v = new CSVFormatter().createVisitor(csv);
            v.visitInfo(loader.getSessionInfoStore().getInfos(), loader.getExecutionDataStore().getContents());
            v.visitBundle(bundle, sources);
            v.visitEnd();
        }

        // per package: covered and total lines and branches, and classes with any line run
        Map<String, long[]> packages = new TreeMap<>();
        for (IClassCoverage c : builder.getClasses()) {
            String pkg = c.getPackageName().replace('/', '.');
            long[] p = packages.computeIfAbsent(pkg, k -> new long[6]);
            ICounter lines = c.getLineCounter();
            ICounter branches = c.getBranchCounter();
            p[0] += lines.getCoveredCount();
            p[1] += lines.getTotalCount();
            p[2] += branches.getCoveredCount();
            p[3] += branches.getTotalCount();
            p[4] += lines.getCoveredCount() > 0 ? 1 : 0;
            p[5] += 1;
        }
        List<String> rows = new ArrayList<>();
        rows.add("package\tlines_covered\tlines\tbranches_covered\tbranches\tclasses_run\tclasses");
        for (Map.Entry<String, long[]> e : packages.entrySet()) {
            long[] p = e.getValue();
            rows.add(e.getKey() + "\t" + p[0] + "\t" + p[1] + "\t" + p[2] + "\t" + p[3] + "\t" + p[4] + "\t" + p[5]);
        }
        java.nio.file.Files.write(new File(out, "packages.tsv").toPath(), rows);
        ICounter l = bundle.getLineCounter();
        ICounter b = bundle.getBranchCounter();
        System.out.printf("java: lines %d of %d (%.1f%%), branches %d of %d (%.1f%%), %d classes%n",
                l.getCoveredCount(), l.getTotalCount(), 100 * l.getCoveredRatio(), b.getCoveredCount(),
                b.getTotalCount(), 100 * b.getCoveredRatio(), bundle.getClassCounter().getTotalCount());
    }
}
