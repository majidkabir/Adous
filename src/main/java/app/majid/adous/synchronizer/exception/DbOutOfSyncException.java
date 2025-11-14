package app.majid.adous.synchronizer.exception;

import app.majid.adous.synchronizer.model.RepoObject;

import java.util.List;

/**
 * Exception thrown when a database has objects that are out of sync with the repository.
 * This prevents accidental overwrites of database changes that haven't been committed to the repository.
 */
public class DbOutOfSyncException extends SynchronizationException {

    private final String dbName;
    private final List<RepoObject> outOfSyncObjects;

    public DbOutOfSyncException(String dbName, List<RepoObject> outOfSyncObjects) {
        super("The database '" + dbName + "' has out-of-sync objects: " + outOfSyncObjects);
        this.dbName = dbName;
        this.outOfSyncObjects = List.copyOf(outOfSyncObjects);
    }

    public String getDbName() {
        return dbName;
    }

    public List<RepoObject> getOutOfSyncObjects() {
        return outOfSyncObjects;
    }
}
