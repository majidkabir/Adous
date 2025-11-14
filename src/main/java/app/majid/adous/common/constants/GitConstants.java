package app.majid.adous.common.constants;

/**
 * Constants used throughout the application for Git operations.
 */
public final class GitConstants {

    private GitConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }

    public static final String SQL_FILE_EXTENSION = ".sql";
    public static final String GO_STATEMENT = "GO";
    public static final String DEFAULT_COMMIT_USERNAME = "Adous System";
    public static final String DEFAULT_COMMIT_EMAIL = "adous@mail.com";
    public static final String DEFAULT_BRANCH = "main";
}

