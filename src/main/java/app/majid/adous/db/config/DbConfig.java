package app.majid.adous.db.config;

import jakarta.validation.constraints.NotBlank;

/**
 * Configuration for a single database connection.
 * Contains all necessary information to establish a JDBC connection.
 */
public record DbConfig(
        @NotBlank(message = "Database URL is required")
        String url,

        @NotBlank(message = "Database username is required")
        String username,

        @NotBlank(message = "Database password is required")
        String password,

        @NotBlank(message = "Database driver class name is required")
        String driverClassName
) {
}
