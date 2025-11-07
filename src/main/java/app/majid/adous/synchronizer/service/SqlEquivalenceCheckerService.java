package app.majid.adous.synchronizer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class SqlEquivalenceCheckerService {

    @Value("${spring.application.database.default-schema}")
    private String defaultSchema;

    public boolean equals(String sqlA, String sqlB) {
        return Objects.equals(normalizeSql(sqlA), normalizeSql(sqlB));
    }

    private String normalizeSql(String sql) {
        if (sql == null) {
            return null;
        }

        StringBuilder normalizedSql = new StringBuilder();
        boolean inSingleLineComment = false;
        boolean inMultiLineComment = false;
        boolean inWhitespace = false;

        int length = sql.length();
        for (int i = 0; i < length; i++) {
            char c = sql.charAt(i);

            if (i < length - 1 && c == '-' && sql.charAt(i + 1) == '-') {
                inSingleLineComment = true;
            }

            if (inSingleLineComment && (c == '\n')) {
                inSingleLineComment = false;
                continue;
            }

            if (i < length - 1 && c == '/' && sql.charAt(i + 1) == '*') {
                inMultiLineComment = true;
            }

            if (inMultiLineComment && i > 0 && c == '/' && sql.charAt(i - 1) == '*') {
                inMultiLineComment = false;
                continue;
            }

            if (inSingleLineComment || inMultiLineComment) {
                continue;
            }

            if (Character.isUpperCase(c)) {
                c = Character.toLowerCase(c);
            }

            if (c == ';') {
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (!inWhitespace) {
                    normalizedSql.append(' ');
                    inWhitespace = true;
                }
            } else {
                normalizedSql.append(c);
                inWhitespace = false;
            }
        }
        String createStatement = selectCreateStatement(normalizedSql.toString());
        String withoutAlter = replaceCreateOrAlterWithCreate(createStatement);
        return removeSchemaPrefixes(withoutAlter);
    }

    private String selectCreateStatement(String sql) {
        String[] sqlParts = sql.split("(?i)\\bGO\\b\\s*");

        for (String part : sqlParts) {
            if (part.toLowerCase().contains("create")) {
                return part.trim();
            }
        }

        return "";
    }

    private String replaceCreateOrAlterWithCreate(String sql) {
        final String create = "create";
        final String orAlter = "or alter";

        int createIndex = sql.indexOf(create);

        if (createIndex != -1) {
            int orAlterIndex = createIndex + create.length();


            if (sql.regionMatches(true, orAlterIndex + 1, orAlter, 0, orAlter.length())) {
                return sql.substring(0, createIndex) + "create" + sql.substring(orAlterIndex + 1 + orAlter.length());
            }
        }

        return sql;
    }

    private String removeSchemaPrefixes(String sql) {
        // Remove schema prefixes from object names (e.g., dbo.)
        return sql.replaceAll(
                "(create|alter)\\s+(procedure|function|view|trigger)\\s+(?:\\[?%s+\\]?\\.)?".formatted(defaultSchema),
                "$1 $2 "
        );
    }
}
