package app.majid.adous.common.constants;

/**
 * Constants used throughout the application for database operations.
 */
public final class DatabaseConstants {

    private DatabaseConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }

    public static final String DEFAULT_SCHEMA = "dbo";
    public static final String SET_ANSI_NULLS_ON = "SET ANSI_NULLS ON;";
    public static final String SET_ANSI_NULLS_OFF = "SET ANSI_NULLS OFF;";
    public static final String SET_QUOTED_IDENTIFIER_ON = "SET QUOTED_IDENTIFIER ON;";
    public static final String SET_QUOTED_IDENTIFIER_OFF = "SET QUOTED_IDENTIFIER OFF;";
    public static final String LINE_SEPARATOR = "\r\n";
}

