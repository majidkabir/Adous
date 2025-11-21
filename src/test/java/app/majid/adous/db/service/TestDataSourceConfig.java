package app.majid.adous.db.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Test configuration for TableAlterScriptGeneratorTest.
 * Provides necessary beans for the test to work with Testcontainers.
 */
@TestConfiguration
public class TestDataSourceConfig {

    @Bean
    public DataSource dataSource() {
        // This will be overridden by @DynamicPropertySource in the test
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlserver://localhost:1433");
        config.setUsername("sa");
        config.setPassword("password");
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}

