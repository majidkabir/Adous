package app.majid.adous.synchronizer.model;

public record DbObject(
        String schema,
        String name,
        DbObjectType type,
        String definition
) {
}
