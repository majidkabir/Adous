package app.majid.adous.synchronizer.exception;

import java.util.List;

/**
 * Thrown when applying repository changes to a database would destroy data
 * (dropping a table or a column) and the caller did not set {@code allowDataLoss}.
 */
public class DataLossException extends SynchronizationException {

    private final String dbName;
    private final List<String> statements;

    public DataLossException(String dbName, List<String> statements) {
        super("Sync to '" + dbName + "' would destroy data; set allowDataLoss=true to apply: "
                + String.join(" ", statements));
        this.dbName = dbName;
        this.statements = List.copyOf(statements);
    }

    public String getDbName() {
        return dbName;
    }

    public List<String> getStatements() {
        return statements;
    }
}
