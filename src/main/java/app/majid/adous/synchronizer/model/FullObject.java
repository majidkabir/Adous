package app.majid.adous.synchronizer.model;

public class FullObject {
    private final String key;
    private final String basePath;
    private final String diffPath;
    String dbDefinition;
    String baseDefinition;
    String diffDefinition;

    public FullObject(String key, String baseRootPath, String diffRootPath) {
        this.key = key; // type/schema/name
        this.basePath = "%s/%s.sql".formatted(baseRootPath, key);
        this.diffPath = "%s/%s.sql".formatted(diffRootPath, key);
    }

    public String getKey() {
        return key;
    }

    public String getBasePath() {
        return basePath;
    }

    public String getDiffPath() {
        return diffPath;
    }

    public String getDbDefinition() {
        return dbDefinition;
    }

    public void setDbDefinition(String dbDefinition) {
        this.dbDefinition = dbDefinition;
    }

    public String getBaseDefinition() {
        return baseDefinition;
    }

    public void setBaseDefinition(String baseDefinition) {
        this.baseDefinition = baseDefinition;
    }

    public String getDiffDefinition() {
        return diffDefinition;
    }

    public void setDiffDefinition(String diffDefinition) {
        this.diffDefinition = diffDefinition;
    }
}
