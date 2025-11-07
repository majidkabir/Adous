package app.majid.adous.git.mapper;

import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import org.springframework.stereotype.Component;

@Component
public class DbObjectMapper {

    public DbObject fromPath(String path, String definition) {
        if (!path.endsWith(".sql")) {
            throw new IllegalArgumentException("Invalid file type: " + path);
        }

        int lastSlash = path.lastIndexOf('/');
        int secondSlash = path.lastIndexOf('/', lastSlash - 1);
        int thirdSlash = path.lastIndexOf('/', secondSlash - 1);

        if (thirdSlash < 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        String type = path.substring(thirdSlash + 1, secondSlash);
        String schema = path.substring(secondSlash + 1, lastSlash);
        String name = path.substring(lastSlash + 1, path.length() - 4);

        return new DbObject(schema, name, DbObjectType.valueOf(type.toUpperCase()), definition);
    }
}
