package app.majid.adous.db.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);

    private final DbProperties dbProperties;

    public DatabaseConfig(DbProperties dbProperties) {
        this.dbProperties = dbProperties;
    }

    @Bean
    public DataSource routingDataSource() {
        var dbs = dbProperties.dbs();

        AbstractRoutingDataSource routing = new DynamicRoutingDataSource();

        Map<Object, Object> dataSources = new HashMap<>();
        for (Map.Entry<String, DbConfig> entry: dbs.entrySet()) {
            DataSource dataSource = buildDataSource(entry.getValue());
            dataSources.put(entry.getKey(), dataSource);
            logger.info("Configured datasource for DB: {}", entry.getKey());
        }

        routing.setTargetDataSources(dataSources);

        DatabaseContextHolder.setAvailableDbs(dbs.keySet());
        // Set default DB only for app startup
        DatabaseContextHolder.setCurrentDb(dbs.keySet().iterator().next());

        return routing;
    }

    private DataSource buildDataSource(DbConfig config) {
        return DataSourceBuilder.create()
                .url(config.url())
                .username(config.username())
                .password(config.password())
                .driverClassName(config.driverClassName())
                .build();
    }

}
