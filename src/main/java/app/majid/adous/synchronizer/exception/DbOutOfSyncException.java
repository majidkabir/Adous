package app.majid.adous.synchronizer.exception;

import app.majid.adous.synchronizer.model.RepoObject;

import java.util.List;

public class DbOutOfSyncException extends  RuntimeException {
    public DbOutOfSyncException(String dbName, List<RepoObject> outOfSyncObjects) {
        super("The DB '" + dbName + "' has out-of-sync objects: " + outOfSyncObjects);
    }
}
