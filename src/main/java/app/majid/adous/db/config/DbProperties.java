package app.majid.adous.db.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Configuration properties for multiple database connections.
 * Maps database names to their connection configurations.
 */
@Validated
@ConfigurationProperties(prefix = "spring.datasources")
public record DbProperties(
        @NotNull
        @Valid
        Map<String, DbConfig> dbs
) {
    /**
     * Constructor that ensures dbs map is never null.
     */
    public DbProperties {
        if (dbs == null) {
            dbs = Map.of();
        }
    }
}
