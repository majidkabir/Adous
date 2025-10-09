package app.majid.adous.git.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github")
public record GitProperties(
        boolean localModeForTest,
        String remoteUri,
        String token,
        String baseRootPath,
        String diffRootPath,
        String prefixPath
) { }

