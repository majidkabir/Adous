package app.majid.adous.synchronizer.model;

public record DbObject(
        String schema,
        String name,
        DbObjectType type,
        String definition
) {

    @Override
    public String toString() {
        return "DbObject[schema=%s, name=%s, type=%s]".formatted(schema, name, type);
    }
}
