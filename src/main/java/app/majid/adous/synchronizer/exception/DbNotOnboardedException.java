package app.majid.adous.synchronizer.exception;

/**
 * Exception thrown when attempting to sync a database that has not been onboarded to the repository.
 */
public class DbNotOnboardedException extends SynchronizationException {

    private final String dbName;

    public DbNotOnboardedException(String dbName) {
        super("The database '" + dbName + "' has not been onboarded to the repository yet");
        this.dbName = dbName;
    }

    public String getDbName() {
        return dbName;
    }
}
