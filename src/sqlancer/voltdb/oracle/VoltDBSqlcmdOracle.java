package sqlancer.voltdb.oracle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.oracle.TestOracleUtils;
import sqlancer.common.oracle.UnexpectedErrorReproducer;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBErrors;
import sqlancer.voltdb.VoltDBOptions;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.ast.VoltDBAlias;
import sqlancer.voltdb.ast.VoltDBExpression;
import sqlancer.voltdb.ast.VoltDBSelect;
import sqlancer.voltdb.gen.VoltDBExpressionGenerator;
import sqlancer.voltdb.gen.VoltDBExpressionGenerator.Kind;

/**
 * sqlcmd against JDBC (ENG-30789, the rebuilt sqlcmd). With {@code --sqlcmd name=path[,name=path...]}, SQLCMD runs a
 * generated query through JDBC and through each sqlcmd ({@code --query}, {@code --output-format=csv},
 * {@code --output-skip-metadata}) against the same cluster, and checks that each sqlcmd prints the rows JDBC returns:
 * each csv field is read back as its column's type (from the JDBC metadata) and compared with JDBC's value, so a
 * difference in how a value is written (a DECIMAL's trailing zeros) is not one in the value. The rows are compared as
 * multisets.
 *
 * <p>
 * The query is a SELECT of columns and expressions with a WHERE clause, or a GROUP BY query with aggregates, as DIFF
 * generates. The database's thread is its only writer, so nothing changes between the JDBC query and the sqlcmd runs.
 * With more than one sqlcmd, their csv text is also compared record by record, as written; a difference there is not a
 * failure (the rebuilt sqlcmd changes some output on purpose) but is counted and logged to
 * {@code logs/voltdb/sqlcmd-differences.log}. At exit SQLCMD prints, for each sqlcmd, how many queries it checked and
 * how many fields were a known sqlcmd issue.
 *
 * <p>
 * Known issues, both in the sqlcmd of 16.1.0-SNAPSHOT and the rebuilt one, in csv only: a BOOLEAN is written as an
 * empty field, TRUE, FALSE and NULL alike (ENG-30895); a TIMESTAMP before 1970 whose microseconds are not whole
 * milliseconds is written as the next millisecond, a minus sign and the microseconds to take off
 * ({@code 01:42:38.818363} as {@code 01:42:38.819-637}, ENG-30896); and a VARCHAR holding {@code \N} is written as NULL
 * is (csv's NULL, by design). While --avoid-known-bugs is true, every BOOLEAN matches an empty field, such a TIMESTAMP
 * is read back by taking the microseconds off, and {@code \N} matches NULL in a VARCHAR column. SQLCMD skips every
 * check with --xdcr (the database may write through the other cluster) and --ssl-truststore (sqlcmd would need its own
 * TLS settings).
 */
public class VoltDBSqlcmdOracle implements TestOracle<VoltDBGlobalState> {

    private static final long TIMEOUT_SECONDS = 120;
    private static final String NULL = "\u2400";
    private static final String KNOWN_BOOLEAN = "BOOLEAN?";
    // ENG-30896: csv writes a TIMESTAMP before 1970 whose microseconds are not whole milliseconds as the next
    // millisecond, a minus sign and the microseconds to take off it: 01:42:38.818363 as 01:42:38.819-637
    private static final Pattern NEGATIVE_MICROS = Pattern.compile("(.*\\.\\d{3})-(\\d{1,3})");
    private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]",
            Locale.ROOT);

    private static final Map<String, Stats> STATS = new LinkedHashMap<>();
    private static final AtomicLong TEXT_DIFFERENCES = new AtomicLong();
    private static final AtomicLong TEXT_SAME = new AtomicLong();

    private final VoltDBGlobalState state;
    private final ExpectedErrors errors;
    private final List<Sqlcmd> sqlcmds = new ArrayList<>();
    private String lastQuery;
    private Reproducer<VoltDBGlobalState> reproducer;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StringBuilder sb = new StringBuilder("SQLCMD checked:");
            synchronized (STATS) {
                STATS.forEach((name, s) -> sb.append(' ').append(name).append(' ').append(s.queries)
                        .append(" queries, ").append(s.rows).append(" rows, ").append(s.knownBoolean)
                        .append(" BOOLEAN fields left empty, ").append(s.knownTimestamp)
                        .append(" TIMESTAMPs before 1970 misprinted, ").append(s.knownNullString)
                        .append(" \\N strings;"));
            }
            if (STATS.size() > 1) {
                sb.append(" text: ").append(TEXT_SAME).append(" same, ").append(TEXT_DIFFERENCES).append(" different");
            }
            System.out.println(sb);
        }));
    }

    private static final class Stats {
        long queries;
        long rows;
        long knownBoolean;
        long knownNullString;
        long knownTimestamp;
    }

    private static final class Sqlcmd {
        final String name;
        final String path;

        Sqlcmd(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    // a csv field as written: its text, and whether it was quoted (an empty BOOLEAN is not; an empty string is)
    private static final class Field {
        final String text;
        final boolean quoted;

        Field(String text, boolean quoted) {
            this.text = text;
            this.quoted = quoted;
        }
    }

    public VoltDBSqlcmdOracle(VoltDBGlobalState state, ExpectedErrors errors) {
        this.state = state;
        this.errors = errors;
        String spec = state.getDbmsSpecificOptions().sqlcmd;
        if (spec != null && !spec.isBlank()) {
            for (String one : spec.split(",")) {
                String[] parts = one.trim().split("=", 2);
                sqlcmds.add(parts.length == 2 ? new Sqlcmd(parts[0].trim(), parts[1].trim())
                        : new Sqlcmd("sqlcmd", parts[0].trim()));
            }
        }
        synchronized (STATS) {
            sqlcmds.forEach(s -> STATS.computeIfAbsent(s.name, n -> new Stats()));
        }
    }

    @Override
    public void check() throws Exception {
        reproducer = null;
        VoltDBOptions options = state.getDbmsSpecificOptions();
        if (sqlcmds.isEmpty() || options.xdcr != null && !options.xdcr.isBlank()
                || options.sslTruststore != null && !options.sslTruststore.isBlank()) {
            throw new IgnoreMeException();
        }
        VoltDBExpressionGenerator gen = new VoltDBExpressionGenerator(state);
        boolean grouped = Randomly.getBoolean();
        // ENG-30531 (see VoltDBTLPOracle): no FULL JOIN in a GROUP BY query
        gen.setNoFullJoins(grouped);
        gen.setTablesAndColumns(TestOracleUtils.getRandomTableNonEmptyTables(state.getSchema()));
        VoltDBSelect select = gen.generateSelect();
        select.setFromList(gen.getTableRefs());
        select.setJoinClauses(gen.getRandomJoinClauses());
        if (grouped) {
            List<VoltDBExpression> grouping = new ArrayList<>(gen.generateGroupingColumns());
            List<VoltDBExpression> fetched = new ArrayList<>(Randomly.subset(grouping));
            if (fetched.isEmpty() || Randomly.getBoolean()) {
                fetched.addAll(gen.generateAggregates());
            }
            select.setFetchColumns(gen.aliased(fetched));
            select.setGroupByExpressions(grouping);
            if (Randomly.getBoolean()) {
                select.setWhereClause(gen.generateBooleanExpression());
            }
        } else {
            List<VoltDBExpression> fetched = new ArrayList<>(gen.generateFetchColumns());
            for (int i = Randomly.smallNumber(); i > 0; i--) {
                fetched.add(
                        new VoltDBAlias(gen.generateExpressionInDomain(Randomly.fromOptions(Kind.values())), "e" + i));
            }
            select.setFetchColumns(fetched);
            select.setWhereClause(gen.generateBooleanExpression());
        }
        String query = select.asString();
        lastQuery = query;
        String described = "-- query through JDBC and sqlcmd: " + query + ';' + System.lineSeparator();
        VoltDBRecheckReproducer.Check check = s -> compare(s, query);
        String problem;
        try {
            problem = check.run(state);
        } catch (AssertionError unexpectedError) {
            reproducer = new UnexpectedErrorReproducer<>(check::run,
                    TestOracleUtils.getUnexpectedErrorMessage(unexpectedError), described);
            throw unexpectedError;
        }
        if (problem != null) {
            reproducer = new VoltDBRecheckReproducer(check, described, problem);
            throw new AssertionError("sqlcmd differs from JDBC: " + problem);
        }
    }

    // null when every sqlcmd prints JDBC's rows
    private String compare(VoltDBGlobalState s, String query) throws SQLException {
        VoltDBConnection connection = (VoltDBConnection) s.getConnection();
        List<Integer> types = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(query)) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                types.add(md.getColumnType(i));
            }
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= types.size(); i++) {
                    Object v = rs.getObject(i);
                    row.add(rs.wasNull() ? NULL : fromJdbc(v, types.get(i - 1)));
                }
                expected.add(String.join(" | ", row));
            }
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw new AssertionError(query, e);
        }
        Collections.sort(expected);
        Map<String, List<String>> texts = new LinkedHashMap<>();
        StringBuilder problems = new StringBuilder();
        for (Sqlcmd sqlcmd : sqlcmds) {
            String out = run(sqlcmd, query);
            List<List<Field>> records = parseCsv(out);
            List<String> actual = new ArrayList<>();
            List<String> text = new ArrayList<>();
            long knownBoolean = 0;
            long knownNullString = 0;
            long knownTimestamp = 0;
            for (List<Field> record : records) {
                if (record.size() != types.size()) {
                    return sqlcmd.name + " printed " + record.size() + " fields, the query has " + types.size()
                            + " columns: " + out;
                }
                List<String> row = new ArrayList<>();
                List<String> raw = new ArrayList<>();
                for (int i = 0; i < types.size(); i++) {
                    Field f = record.get(i);
                    raw.add(f.quoted ? '"' + f.text + '"' : f.text);
                    String value = fromCsv(f, types.get(i));
                    Matcher m = types.get(i) == Types.TIMESTAMP ? NEGATIVE_MICROS.matcher(f.text) : null;
                    if (m != null && m.matches() && state.getDbmsSpecificOptions().avoidKnownBugs) {
                        Instant at = LocalDateTime.parse(m.group(1), MILLIS).toInstant(ZoneOffset.UTC);
                        value = "T:"
                                + (at.getEpochSecond() * 1_000_000L + at.getNano() / 1000 - Long.parseLong(m.group(2)));
                        knownTimestamp++;
                    }
                    if (KNOWN_BOOLEAN.equals(value)) {
                        knownBoolean++;
                    }
                    row.add(value);
                }
                actual.add(String.join(" | ", row));
                text.add(String.join(",", raw));
            }
            Collections.sort(actual);
            Collections.sort(text);
            texts.put(sqlcmd.name, text);
            List<String> want = expected;
            if (state.getDbmsSpecificOptions().avoidKnownBugs) {
                // the known issues (see the class comment): fold what sqlcmd cannot show into what it shows
                actual = foldKnown(actual, types);
                want = foldKnown(want, types);
                Collections.sort(actual);
                Collections.sort(want);
                for (String row : expected) {
                    knownNullString += row.contains("S:\\N") ? 1 : 0;
                }
            }
            if (!actual.equals(want)) {
                problems.append(sqlcmd.name).append(": rows from JDBC: ").append(want.size()).append(", from ")
                        .append(sqlcmd.name).append(": ").append(actual.size()).append(System.lineSeparator())
                        .append("only from JDBC: ").append(VoltDBRows.difference(want, actual))
                        .append(System.lineSeparator()).append("only from ").append(sqlcmd.name).append(": ")
                        .append(VoltDBRows.difference(actual, want)).append(System.lineSeparator());
            }
            synchronized (STATS) {
                Stats stats = STATS.get(sqlcmd.name);
                stats.queries++;
                stats.rows += records.size();
                stats.knownBoolean += knownBoolean;
                stats.knownNullString += knownNullString;
                stats.knownTimestamp += knownTimestamp;
            }
        }
        if (texts.size() > 1) {
            compareText(query, texts);
        }
        return problems.length() == 0 ? null : problems.toString();
    }

    // with --avoid-known-bugs: every BOOLEAN, NULL or not, and an empty BOOLEAN field, become one value (ENG-30895);
    // and a VARCHAR \N becomes NULL
    private static List<String> foldKnown(List<String> rows, List<Integer> types) {
        List<String> folded = new ArrayList<>();
        for (String row : rows) {
            String[] values = row.split(" \\| ", -1);
            for (int i = 0; i < values.length && i < types.size(); i++) {
                if (types.get(i) == Types.BOOLEAN) {
                    values[i] = KNOWN_BOOLEAN;
                } else if ("S:\\N".equals(values[i])) {
                    values[i] = NULL;
                }
            }
            folded.add(String.join(" | ", values));
        }
        return folded;
    }

    private static void compareText(String query, Map<String, List<String>> texts) {
        List<String> first = null;
        boolean same = true;
        for (List<String> t : texts.values()) {
            if (first == null) {
                first = t;
            } else if (!first.equals(t)) {
                same = false;
            }
        }
        if (same) {
            TEXT_SAME.incrementAndGet();
            return;
        }
        TEXT_DIFFERENCES.incrementAndGet();
        StringBuilder sb = new StringBuilder("-- ").append(query).append(';').append(System.lineSeparator());
        texts.forEach((name, t) -> {
            sb.append("-- ").append(name).append(':').append(System.lineSeparator());
            t.stream().limit(20).forEach(r -> sb.append(r).append(System.lineSeparator()));
            if (t.size() > 20) {
                sb.append("... ").append(t.size() - 20).append(" more").append(System.lineSeparator());
            }
        });
        Path log = Paths.get("logs", "voltdb", "sqlcmd-differences.log");
        synchronized (TEXT_DIFFERENCES) {
            try {
                Files.createDirectories(log.getParent());
                try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                    w.println(sb);
                }
            } catch (IOException e) {
                System.err.println("SQLCMD: cannot write " + log + ": " + e);
            }
        }
    }

    // runs the query through one sqlcmd; its stdout, the csv; an error skips the check unless JDBC ran the query
    private String run(Sqlcmd sqlcmd, String query) {
        VoltDBOptions options = state.getDbmsSpecificOptions();
        List<String> command = new ArrayList<>();
        command.add(sqlcmd.path);
        String servers = options.servers;
        if (servers == null || servers.isBlank()) {
            String host = state.getOptions().getHost() == null ? VoltDBOptions.DEFAULT_HOST
                    : state.getOptions().getHost();
            int port = state.getOptions().getPort() <= 0 ? VoltDBOptions.DEFAULT_PORT : state.getOptions().getPort();
            servers = host + ':' + port;
        }
        command.add("--servers=" + servers.trim());
        String user = state.getOptions().getUserName();
        if (options.security && user != null && !user.isBlank()) {
            command.add("--user=" + user);
            command.add("--password=" + state.getOptions().getPassword());
        }
        command.add("--output-format=csv");
        command.add("--output-skip-metadata");
        command.add("--query=" + query + ';');
        ProcessBuilder pb = new ProcessBuilder(command);
        // the kit's bin first: its dbenv prints a notice on stdout when another voltdb comes first on PATH, or none
        String bin = new File(sqlcmd.path).getAbsoluteFile().getParent();
        pb.environment().put("PATH", bin + File.pathSeparator + pb.environment().getOrDefault("PATH", ""));
        File err;
        try {
            err = File.createTempFile("sqlcmd", ".err");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        err.deleteOnExit();
        pb.redirectError(err);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        try {
            Process p = pb.start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AssertionError(sqlcmd.name + " still running after " + TIMEOUT_SECONDS + " s: " + query);
            }
            String stderr = new String(Files.readAllBytes(err.toPath()), StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0 || !stderr.isEmpty()) {
                // the cluster changed under it: a node failure, a rejoin, another thread's DDL
                SQLException e = new SQLException(stderr);
                if (VoltDBErrors.isNodeFailure(e) || VoltDBErrors.isRetryable(e) || errors.errorIsExpected(stderr)) {
                    throw new IgnoreMeException();
                }
                throw new AssertionError(
                        sqlcmd.name + " failed (exit code " + p.exitValue() + ") on a query JDBC ran: " + stderr);
            }
            return out;
        } catch (IOException e) {
            throw new AssertionError(sqlcmd.name + ": cannot run " + sqlcmd.path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IgnoreMeException();
        } finally {
            err.delete();
        }
    }

    // the records of parseCsv, each field as written: in double quotes if it was quoted (for the unit test)
    static List<List<String>> parseCsvForTest(String csv) {
        List<List<String>> out = new ArrayList<>();
        for (List<Field> r : parseCsv(csv)) {
            List<String> row = new ArrayList<>();
            r.forEach(f -> row.add(f.quoted ? '"' + f.text + '"' : f.text));
            out.add(row);
        }
        return out;
    }

    // RFC 4180: fields in double quotes may hold commas, quotes (doubled) and line breaks; every line is a record, an
    // empty one too (a lone empty BOOLEAN, ENG-30895), and a zero-row result is no text at all
    static List<List<Field>> parseCsv(String csv) {
        List<List<Field>> records = new ArrayList<>();
        List<Field> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean inQuotes = false;
        boolean any = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                quoted = true;
                any = true;
            } else if (c == ',') {
                record.add(new Field(field.toString(), quoted));
                field.setLength(0);
                quoted = false;
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(new Field(field.toString(), quoted));
                records.add(record);
                record = new ArrayList<>();
                field.setLength(0);
                quoted = false;
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || field.length() > 0) {
            record.add(new Field(field.toString(), quoted));
            records.add(record);
        }
        return records;
    }

    // one value as a comparable string, from JDBC
    private static String fromJdbc(Object v, int type) {
        switch (type) {
        case Types.TINYINT:
        case Types.SMALLINT:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.DECIMAL:
        case Types.NUMERIC:
            return "N:" + number(new BigDecimal(v.toString()));
        case Types.FLOAT:
        case Types.DOUBLE:
        case Types.REAL:
            return "F:" + ((Number) v).doubleValue();
        case Types.TIMESTAMP:
            if (v instanceof Timestamp) {
                Timestamp t = (Timestamp) v;
                return "T:" + (Math.floorDiv(t.getTime(), 1000L) * 1_000_000L + t.getNanos() / 1000);
            }
            return "T:" + v;
        case Types.DATE:
            if (v instanceof java.util.Date) {
                return "D:" + Instant.ofEpochMilli(((java.util.Date) v).getTime()).atZone(ZoneOffset.UTC).toLocalDate();
            }
            return "D:" + v;
        case Types.BOOLEAN:
        case Types.BIT:
            return "B:" + v;
        case Types.VARBINARY:
        case Types.BINARY:
            if (v instanceof byte[]) {
                StringBuilder sb = new StringBuilder("X:");
                for (byte b : (byte[]) v) {
                    sb.append(String.format("%02X", b));
                }
                return sb.toString();
            }
            return "X:" + v;
        default:
            return "S:" + v;
        }
    }

    // the same value, from a csv field of the column's type; a field that does not parse stays as written
    private static String fromCsv(Field f, int type) {
        String s = f.text;
        if ("\\N".equals(s) && type != Types.VARCHAR && type != Types.CHAR) {
            return NULL;
        }
        try {
            switch (type) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.DECIMAL:
            case Types.NUMERIC:
                return "N:" + number(new BigDecimal(s));
            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.REAL:
                return "F:" + Double.parseDouble(s);
            case Types.TIMESTAMP:
                LocalDateTime t = LocalDateTime.parse(s, TIMESTAMP);
                Instant i = t.toInstant(ZoneOffset.UTC);
                return "T:" + (i.getEpochSecond() * 1_000_000L + i.getNano() / 1000);
            case Types.DATE:
                return "D:" + LocalDate.parse(s);
            case Types.BOOLEAN:
            case Types.BIT:
                if (s.isEmpty() && !f.quoted) {
                    return KNOWN_BOOLEAN;
                }
                return "B:" + Boolean.parseBoolean(s);
            case Types.VARBINARY:
            case Types.BINARY:
                return "X:" + s.toUpperCase(Locale.ROOT);
            default:
                // a VARCHAR \N: NULL, or the string \N (a known issue); NULL unless --avoid-known-bugs folds them
                return "\\N".equals(s) ? NULL : "S:" + s;
            }
        } catch (RuntimeException e) {
            return "?:" + s;
        }
    }

    private static String number(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return s.signum() == 0 ? "0" : s.toPlainString();
    }

    @Override
    public Reproducer<VoltDBGlobalState> getLastReproducer() {
        return reproducer;
    }

    @Override
    public String getLastQueryString() {
        return lastQuery;
    }
}
