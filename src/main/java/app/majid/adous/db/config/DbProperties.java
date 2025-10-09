package app.majid.adous.db.config;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "spring.datasources")
public record DbProperties(Map<String, DbConfig> dbs) {}
