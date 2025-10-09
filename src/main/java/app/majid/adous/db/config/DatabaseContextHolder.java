package app.majid.adous.db.config;

import java.util.Set;

public class DatabaseContextHolder {
    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();
    private static Set<String> availableDbs;

    public static void setAvailableDbs(Set<String> dbs) {
        availableDbs = dbs;
    }

    public static Set<String> getAvailableDbs() {
        return availableDbs;
    }

    public static void setCurrentDb(String db) {
        if (availableDbs != null && availableDbs.contains(db)) {
            contextHolder.set(db);
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
