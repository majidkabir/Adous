package app.majid.adous.git.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for Git repository integration.
 * Defines how the application interacts with the Git repository.
 */
@ConfigurationProperties(prefix = "github")
public record GitProperties(
        /** Whether to use local mode for testing (skips remote operations) */
        boolean localModeForTest,

        /** Git remote repository URI */
        String remoteUri,

        /** Authentication token for Git operations */
        String token,

        /** Root path for base (canonical) database objects in the repository */
        @NotBlank(message = "Base root path is required")
        String baseRootPath,

        /** Root path for database-specific overrides in the repository */
        @NotBlank(message = "Diff root path is required")
        String diffRootPath,

        /** Optional prefix path for all repository operations */
        String prefixPath,

        /** Username for Git commits */
        @DefaultValue("Adous System")
        @NotBlank
        String commitUsername,

        /** Email for Git commits */
        @DefaultValue("adous@mail.com")
        @NotBlank
        @Email
        String commitEmail,

        /** Default branch name for Git operations */
        @DefaultValue("main")
        @NotBlank
        String defaultBranch
) { }

