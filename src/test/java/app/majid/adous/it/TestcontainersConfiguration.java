package app.majid.adous.it;

import app.majid.adous.db.config.DbConfig;
import app.majid.adous.db.config.DbProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MSSQLServerContainer;

import java.util.Map;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @SuppressWarnings("resource")
    @Bean(destroyMethod = "stop")
    MSSQLServerContainer<?> mssqlServerContainer() {
        var container = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                .acceptLicense()
                .withInitScript("init-databases.sql");
        container.start();
        return container;
    }

    @Bean
    @Primary
    public DbProperties dbProperties(MSSQLServerContainer<?> mssqlServerContainer) {
        DbConfig db1Config = createDbConfig("dbtest1", mssqlServerContainer);
        DbConfig db2Config = createDbConfig("dbtest2", mssqlServerContainer);

        Map<String, DbConfig> dbs = Map.of(
                "db1", db1Config,
                "db2", db2Config
        );
        return new DbProperties(dbs);
    }

    private DbConfig createDbConfig(String dbName, MSSQLServerContainer<?> container) {
        String url = container.getJdbcUrl() + ";databaseName=" + dbName;
        return new DbConfig(
                url,
                container.getUsername(),
                container.getPassword(),
                "com.microsoft.sqlserver.jdbc.SQLServerDriver"
        );
    }
}
