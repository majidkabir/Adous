package app.majid.adous.db.config;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Holds the database selected for the current thread.
 * <p>
 * Database names are case-insensitive: they are normalized with {@link #normalize(String)}
 * everywhere (datasource keys, routing lookups, Git tags and diff paths) so that a database
 * configured as {@code MyDb} and requested as {@code mydb} resolve to the same datasource.
 */
public class DatabaseContextHolder {
    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();
    private static Set<String> availableDbs;

    /**
     * Canonical form of a database name used for routing keys, Git tags and paths.
     */
    public static String normalize(String db) {
        return db == null ? null : db.toLowerCase(Locale.ROOT);
    }

    public static void setAvailableDbs(Set<String> dbs) {
        availableDbs = dbs.stream()
                .map(DatabaseContextHolder::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> getAvailableDbs() {
        return availableDbs;
    }

    public static void setCurrentDb(String db) {
        String normalized = normalize(db);
        if (availableDbs != null && availableDbs.contains(normalized)) {
            contextHolder.set(normalized);
        } else {
            throw new IllegalArgumentException("Database " + db + " does not exist");
        }
    }

    public static String getCurrentDb() {
        return contextHolder.get();
    }

    public static void clear() {
        contextHolder.remove();
    }
}
