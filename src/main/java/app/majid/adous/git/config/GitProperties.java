package app.majid.adous.git.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "github")
public record GitProperties(
        boolean localModeForTest,
        String remoteUri,
        String token,
        String baseRootPath,
        String diffRootPath,
        String prefixPath,
        @DefaultValue("Adous System")
        String commitUsername,
        @DefaultValue("adous@mail.com")
        String commitEmail,
        @DefaultValue("main")
        String defaultBranch
) { }

