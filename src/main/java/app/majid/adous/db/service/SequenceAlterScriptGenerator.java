package app.majid.adous.db.service;

import app.majid.adous.synchronizer.model.DbObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates {@code ALTER SEQUENCE} scripts so an existing sequence keeps its current value
 * when its definition changes. Dropping and recreating it would restart numbering at
 * {@code START WITH} (re-issuing values already used as keys) and fails outright when a
 * column default uses {@code NEXT VALUE FOR} the sequence.
 */
@Service
public class SequenceAlterScriptGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SequenceAlterScriptGenerator.class);

    private static final Pattern CREATE_SEQUENCE = Pattern.compile("\\bCREATE\\s+SEQUENCE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_TYPE = Pattern.compile(
            "\\bAS\\s+\\[?(\\w+)]?(?:\\s*\\(\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern START_WITH = Pattern.compile("\\bSTART\\s+WITH\\s+(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCREMENT_BY = Pattern.compile("\\bINCREMENT\\s+BY\\s+(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_VALUE = Pattern.compile("(?<!\\bNO\\s)\\bMINVALUE\\s+(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAX_VALUE = Pattern.compile("(?<!\\bNO\\s)\\bMAXVALUE\\s+(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_CYCLE = Pattern.compile("\\bNO\\s+CYCLE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CYCLE = Pattern.compile("\\bCYCLE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_CACHE = Pattern.compile("\\bNO\\s+CACHE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CACHE = Pattern.compile("\\bCACHE\\b(?:\\s+(\\d+))?", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;

    public SequenceAlterScriptGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Sequence options parsed from a {@code CREATE SEQUENCE} definition; nulls mean "not specified". */
    record SequenceDefinition(String dataType, Integer precision, Long startWith, Long incrementBy,
                              Long minValue, Long maxValue, boolean cycle, String cache) {
    }

    /** The parts of an existing sequence that decide how it can be changed. */
    private record ExistingSequence(String dataType, int precision, long startValue, boolean used) {
    }

    /**
     * Returns an {@code ALTER SEQUENCE} script that applies the definition while keeping the
     * current value, or empty when the sequence must be (re)created instead: it doesn't exist,
     * its data type changes (which {@code ALTER SEQUENCE} can't do), or the definition can't be parsed.
     */
    public Optional<String> generateAlterScript(DbObject sequence) {
        Optional<SequenceDefinition> parsed = parse(sequence.definition());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        SequenceDefinition target = parsed.get();

        List<ExistingSequence> existing = jdbcTemplate.query("""
                SELECT TYPE_NAME(system_type_id) AS data_type,
                       precision,
                       CAST(start_value AS BIGINT) AS start_value,
                       CASE WHEN last_used_value IS NULL THEN 0 ELSE 1 END AS used
                FROM sys.sequences
                WHERE schema_id = SCHEMA_ID(?) AND name = ?
                """, (rs, _) -> new ExistingSequence(
                        rs.getString("data_type"),
                        rs.getInt("precision"),
                        rs.getLong("start_value"),
                        rs.getInt("used") == 1),
                sequence.schema(), sequence.name());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ExistingSequence current = existing.getFirst();

        if (!current.dataType().equalsIgnoreCase(target.dataType())
                || (target.precision() != null && target.precision() != current.precision())) {
            logger.warn("Sequence [{}].[{}] changes data type ({} -> {}); it will be recreated and restart at START WITH",
                    sequence.schema(), sequence.name(), current.dataType(), target.dataType());
            return Optional.empty();
        }

        StringBuilder script = new StringBuilder("ALTER SEQUENCE [%s].[%s]".formatted(sequence.schema(), sequence.name()));

        if (target.startWith() != null && target.startWith() != current.startValue()) {
            if (current.used()) {
                logger.warn("Sequence [{}].[{}] START WITH changed ({} -> {}) but the sequence is in use; "
                                + "keeping its current value instead of restarting",
                        sequence.schema(), sequence.name(), current.startValue(), target.startWith());
            } else {
                script.append("\n    RESTART WITH ").append(target.startWith());
            }
        }
        script.append("\n    INCREMENT BY ").append(target.incrementBy() == null ? 1 : target.incrementBy());
        script.append("\n    ").append(target.minValue() == null ? "NO MINVALUE" : "MINVALUE " + target.minValue());
        script.append("\n    ").append(target.maxValue() == null ? "NO MAXVALUE" : "MAXVALUE " + target.maxValue());
        script.append("\n    ").append(target.cycle() ? "CYCLE" : "NO CYCLE");
        script.append("\n    ").append(target.cache());
        script.append(";\nGO\n");

        return Optional.of(script.toString());
    }

    /**
     * Parses a {@code CREATE SEQUENCE} definition. Options left out of the definition are
     * returned as null (or their SQL Server defaults: bigint, NO CYCLE, CACHE).
     */
    static Optional<SequenceDefinition> parse(String definition) {
        if (definition == null) {
            return Optional.empty();
        }
        String sql = stripComments(definition);
        Matcher create = CREATE_SEQUENCE.matcher(sql);
        if (!create.find()) {
            return Optional.empty();
        }
        String body = sql.substring(create.end());

        Matcher type = DATA_TYPE.matcher(body);
        String dataType = "bigint";
        Integer precision = null;
        if (type.find()) {
            dataType = type.group(1).toLowerCase(Locale.ROOT);
            precision = type.group(2) == null ? null : Integer.valueOf(type.group(2));
        }

        String cache;
        if (NO_CACHE.matcher(body).find()) {
            cache = "NO CACHE";
        } else {
            Matcher cacheMatcher = CACHE.matcher(body);
            cache = cacheMatcher.find() && cacheMatcher.group(1) != null ? "CACHE " + cacheMatcher.group(1) : "CACHE";
        }

        return Optional.of(new SequenceDefinition(
                dataType,
                precision,
                longOrNull(START_WITH, body),
                longOrNull(INCREMENT_BY, body),
                longOrNull(MIN_VALUE, body),
                longOrNull(MAX_VALUE, body),
                !NO_CYCLE.matcher(body).find() && CYCLE.matcher(body).find(),
                cache));
    }

    private static Long longOrNull(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }
}
