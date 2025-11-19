package app.majid.adous.synchronizer.model;

public record RepoObject(
        String path,
        String definition
) {

    @Override
    public String toString() {
        return "RepoObject[path=%s]".formatted(path);
    }
}
