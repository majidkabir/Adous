package app.majid.adous.synchronizer.db;

import app.majid.adous.synchronizer.model.DbObject;

import java.util.List;

public interface DatabaseService {

    /**
     * Retrieves all replaceable database objects from the database.
     *
     * @return List of replaceable database objects with their definitions
     */
    List<DbObject> getDbObjects();

    /**
     * Applies database changes transactionally.
     *
     * @param dbChanges List of database objects to apply
     * @throws org.springframework.dao.DataAccessException if database operation fails
     */
    void applyChangesToDatabase(List<DbObject> dbChanges);

    /**
     * Returns the statements that applying these changes would run and that can destroy
     * existing data (e.g. dropping a table or a column). Empty when the changes are safe.
     *
     * @param dbChanges List of database objects that would be applied
     */
    List<String> findDataLossOperations(List<DbObject> dbChanges);
}
