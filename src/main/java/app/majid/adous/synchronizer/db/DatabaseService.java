package app.majid.adous.synchronizer.db;

import app.majid.adous.synchronizer.model.DbObject;

import java.util.List;

public interface DatabaseService {

    List<DbObject> getDbObjects();

    void applyChangesToDatabase(List<DbObject> dbChanges);
}
