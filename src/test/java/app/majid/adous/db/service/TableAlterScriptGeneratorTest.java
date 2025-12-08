package app.majid.adous.db.service;

import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for TableAlterScriptGenerator.
 * Uses Testcontainers to spin up a real SQL Server instance.
 */
@Testcontainers
class TableAlterScriptGeneratorTest {

    @Container
    static MSSQLServerContainer<?> mssqlServer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    private static JdbcTemplate jdbcTemplate;
    private TableAlterScriptGenerator alterScriptGenerator;

    @BeforeEach
    void setUp() {
        if (jdbcTemplate == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(mssqlServer.getJdbcUrl());
            config.setUsername(mssqlServer.getUsername());
            config.setPassword(mssqlServer.getPassword());
            config.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            config.setMaximumPoolSize(5);
            DataSource dataSource = new HikariDataSource(config);
            jdbcTemplate = new JdbcTemplate(dataSource);
        }

        alterScriptGenerator = new TableAlterScriptGenerator(jdbcTemplate);

        cleanupTables();
    }

    private void cleanupTables() {
        try {
            jdbcTemplate.execute("IF OBJECT_ID('dbo.test_orders', 'U') IS NOT NULL DROP TABLE dbo.test_orders");
            jdbcTemplate.execute("IF OBJECT_ID('dbo.test_users', 'U') IS NOT NULL DROP TABLE dbo.test_users");
            jdbcTemplate.execute("IF OBJECT_ID('dbo.test_products', 'U') IS NOT NULL DROP TABLE dbo.test_products");
        } catch (Exception e) {
            System.err.println("Cleanup error: " + e.getMessage());
        }
    }

    @Nested
    @DisplayName("New Table Creation Tests")
    class NewTableCreationTests {

        @Test
        @DisplayName("Should return CREATE TABLE script when table doesn't exist")
        void shouldReturnCreateTableForNewTable() {
            // Arrange
            String createTableDef = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        [email] nvarchar(255) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, createTableDef);

            // Act
            String result = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(result);
            assertTrue(result.contains("CREATE TABLE"));
            assertTrue(result.contains("[test_users]"));
            assertEquals(createTableDef, result, "Should return exact CREATE TABLE definition for new table");
        }

        @Test
        @DisplayName("Should execute generated CREATE TABLE script successfully")
        void shouldExecuteCreateTableScript() {
            // Arrange
            String createTableDef = """
                    CREATE TABLE [dbo].[test_products]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [name] nvarchar(200) NOT NULL,
                        [price] decimal(10,2) NOT NULL,
                        CONSTRAINT [PK_test_products] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_products", DbObjectType.TABLE, createTableDef);

            // Act
            String script = alterScriptGenerator.generateAlterScript(tableObject);
            jdbcTemplate.execute(script.replace("GO", ""));

            // Assert
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys.tables WHERE name = 'test_products'",
                    Integer.class
            );
            assertEquals(1, count, "Table should be created");
        }
    }

    @Nested
    @DisplayName("Column Modification Tests")
    class ColumnModificationTests {

        @Test
        @DisplayName("Should generate ADD COLUMN statement for new column")
        void shouldGenerateAddColumnStatement() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(100) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        [email] nvarchar(255) NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(alterScript);
            assertTrue(alterScript.contains("ADD [email]"), "Should contain ADD COLUMN statement");
            assertTrue(alterScript.contains("nvarchar(255)"), "Should include correct data type");
            assertTrue(alterScript.contains("NULL"), "Should specify nullability");
        }

        @Test
        @DisplayName("Should generate DROP COLUMN statement for removed column")
        void shouldGenerateDropColumnStatement() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(100) NOT NULL,
                        old_field NVARCHAR(50) NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(alterScript);
            assertTrue(alterScript.contains("DROP COLUMN [old_field]"), "Should contain DROP COLUMN statement");
        }

        @Test
        @DisplayName("Should generate ALTER COLUMN statement for data type change")
        void shouldGenerateAlterColumnForDataTypeChange() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(50) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(alterScript);
            assertTrue(alterScript.contains("ALTER COLUMN [username]"), "Should contain ALTER COLUMN statement");
            assertTrue(alterScript.contains("nvarchar(100)"), "Should include new data type");
        }

        @Test
        @DisplayName("Should generate ALTER COLUMN statement for nullability change")
        void shouldGenerateAlterColumnForNullabilityChange() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        email NVARCHAR(255) NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [email] nvarchar(255) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(alterScript);
            assertTrue(alterScript.contains("ALTER COLUMN [email]"), "Should contain ALTER COLUMN statement");
            assertTrue(alterScript.contains("NOT NULL"), "Should specify NOT NULL");
        }

        @Test
        @DisplayName("Should execute ALTER COLUMN script successfully")
        void shouldExecuteAlterColumnScript() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(50) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);
            jdbcTemplate.execute("INSERT INTO dbo.test_users (username) VALUES ('testuser')");

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(200) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);
            String[] statements = alterScript.split("GO");
            for (String stmt : statements) {
                if (!stmt.trim().isEmpty()) {
                    jdbcTemplate.execute(stmt.trim());
                }
            }

            // Assert
            String username = jdbcTemplate.queryForObject(
                    "SELECT username FROM dbo.test_users WHERE id = 1",
                    String.class
            );
            assertEquals("testuser", username, "Data should be preserved after ALTER COLUMN");

            Integer maxLength = jdbcTemplate.queryForObject("""
                    SELECT c.max_length / 2
                    FROM sys.columns c
                    JOIN sys.tables t ON c.object_id = t.object_id
                    WHERE t.name = 'test_users' AND c.name = 'username'
                    """, Integer.class);
            assertEquals(200, maxLength, "Column length should be updated to 200");
        }
    }

    @Nested
    @DisplayName("Primary Key Modification Tests")
    class PrimaryKeyModificationTests {

        @Test
        @DisplayName("Should parse PRIMARY KEY from CREATE TABLE correctly")
        void shouldParsePrimaryKeyCorrectly() {
            // Arrange
            String createTableSql = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id], [username])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, createTableSql);

            // Act
            String result = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(result);
            assertTrue(result.contains("PRIMARY KEY"), "Should contain PRIMARY KEY in result");
        }

        @Test
        @DisplayName("Should generate DROP and ADD PRIMARY KEY when changed")
        void shouldGenerateDropAndAddPrimaryKey() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT NOT NULL,
                        username NVARCHAR(100) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id], [username])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(alterScript);
            assertTrue(alterScript.contains("DROP CONSTRAINT [pk_test_users]"),
                    "Should drop existing primary key. Actual script: " + alterScript);
            assertTrue(alterScript.contains("ADD CONSTRAINT [pk_test_users] PRIMARY KEY ([id], [username])"),
                    "Should add new composite primary key");
        }
    }

    @Nested
    @DisplayName("No Changes Tests")
    class NoChangesTests {

        @Test
        @DisplayName("Should return empty string when table structure matches")
        void shouldReturnEmptyWhenNoChanges() {
            // Arrange - Create table
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(100) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            // Git definition matches database
            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertEquals("", alterScript, "Should return empty string when no changes needed");
        }
    }

    @Nested
    @DisplayName("Complex Scenario Tests")
    class ComplexScenarioTests {

        @Test
        @DisplayName("Should handle multiple column changes in single alter script")
        void shouldHandleMultipleColumnChanges() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(50) NOT NULL,
                        old_field NVARCHAR(100) NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        [email] nvarchar(255) NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(alterScript);
            assertTrue(alterScript.contains("DROP COLUMN [old_field]"), "Should drop old_field");
            assertTrue(alterScript.contains("ADD [email]"), "Should add email");
            assertTrue(alterScript.contains("ALTER COLUMN [username]"), "Should alter username type");
        }

        @Test
        @DisplayName("Should preserve data through complex schema evolution")
        void shouldPreserveDataThroughComplexChanges() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(50) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);
            jdbcTemplate.execute("INSERT INTO dbo.test_users (username) VALUES ('alice')");
            jdbcTemplate.execute("INSERT INTO dbo.test_users (username) VALUES ('bob')");

            // Git definition with changes
            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        [email] nvarchar(255) NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);
            String[] statements = alterScript.split("GO");
            for (String stmt : statements) {
                if (!stmt.trim().isEmpty()) {
                    jdbcTemplate.execute(stmt.trim());
                }
            }

            // Assert - Verify data is preserved
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dbo.test_users",
                    Integer.class
            );
            assertEquals(2, count, "Both rows should still exist");

            String username1 = jdbcTemplate.queryForObject(
                    "SELECT username FROM dbo.test_users WHERE id = 1",
                    String.class
            );
            assertEquals("alice", username1, "First user's data should be preserved");

            String username2 = jdbcTemplate.queryForObject(
                    "SELECT username FROM dbo.test_users WHERE id = 2",
                    String.class
            );
            assertEquals("bob", username2, "Second user's data should be preserved");

            // Verify new column exists
            Integer emailCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM sys.columns c
                    JOIN sys.tables t ON c.object_id = t.object_id
                    WHERE t.name = 'test_users' AND c.name = 'email'
                    """, Integer.class);
            assertEquals(1, emailCount, "Email column should be added");
        }

        @Test
        @DisplayName("Should handle table with foreign key relationships")
        void shouldHandleTableWithForeignKeys() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(100) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_orders (
                        id INT IDENTITY(1,1) NOT NULL,
                        user_id INT NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        CONSTRAINT PK_test_orders PRIMARY KEY (id),
                        CONSTRAINT FK_test_orders_users FOREIGN KEY (user_id) REFERENCES dbo.test_users(id)
                    )
                    """);

            jdbcTemplate.execute("INSERT INTO dbo.test_users (username) VALUES ('alice')");
            jdbcTemplate.execute("INSERT INTO dbo.test_orders (user_id, amount) VALUES (1, 99.99)");

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_orders]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [user_id] int NOT NULL,
                        [amount] decimal(10,2) NOT NULL,
                        [status] nvarchar(50) NULL,
                        CONSTRAINT [PK_test_orders] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_orders", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);
            String[] statements = alterScript.split("GO");
            for (String stmt : statements) {
                if (!stmt.trim().isEmpty()) {
                    jdbcTemplate.execute(stmt.trim());
                }
            }

            // Assert
            Integer orderCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dbo.test_orders",
                    Integer.class
            );
            assertEquals(1, orderCount, "Order should still exist");

            // Verify FK still works (can't insert invalid user_id)
            assertThrows(Exception.class, () -> {
                jdbcTemplate.execute("INSERT INTO dbo.test_orders (user_id, amount, status) VALUES (999, 50.00, 'pending')");
            }, "Foreign key constraint should still be enforced");
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle identity columns correctly")
        void shouldHandleIdentityColumns() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(100) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            // Git definition - identity column unchanged
            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,10) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        [email] nvarchar(255) NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertFalse(alterScript.contains("ALTER COLUMN [id]"),
                    "Should not try to alter identity column");
        }

        @Test
        @DisplayName("Should handle case-insensitive column names")
        void shouldHandleCaseInsensitiveColumnNames() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        Id INT IDENTITY(1,1) NOT NULL,
                        UserName NVARCHAR(100) NOT NULL,
                        CONSTRAINT PK_test_users PRIMARY KEY (Id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertEquals("", alterScript, "Should handle case-insensitive column matching");
        }

        @Test
        @DisplayName("Should drop CHECK constraints before dropping column")
        void shouldDropCheckConstraintsBeforeDroppingColumn() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_products (
                        id INT IDENTITY(1,1) NOT NULL,
                        name NVARCHAR(200) NOT NULL,
                        price DECIMAL(10,2) NOT NULL,
                        stock_quantity INT NOT NULL DEFAULT 0,
                        CONSTRAINT PK_test_products PRIMARY KEY (id),
                        CONSTRAINT CK_Products_Price CHECK (price >= 0),
                        CONSTRAINT CK_Products_Stock CHECK (stock_quantity >= 0)
                    )
                    """);

            jdbcTemplate.execute("INSERT INTO dbo.test_products (name, price, stock_quantity) VALUES ('Product 1', 99.99, 10)");

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_products]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [name] nvarchar(200) NOT NULL,
                        [stock_quantity] int NOT NULL,
                        CONSTRAINT [PK_test_products] PRIMARY KEY ([id]),
                        CONSTRAINT [CK_Products_Stock] CHECK ([stock_quantity] >= 0)
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_products", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(alterScript);
            assertTrue(alterScript.contains("DROP CONSTRAINT"),
                "Should contain DROP CONSTRAINT statement for CK_Products_Price. Actual script:\n" + alterScript);
            assertTrue(alterScript.contains("DROP COLUMN [price]"),
                "Should contain DROP COLUMN statement");

            // Verify constraint is dropped before column
            int constraintDropIndex = alterScript.indexOf("DROP CONSTRAINT");
            int columnDropIndex = alterScript.indexOf("DROP COLUMN [price]");
            assertTrue(constraintDropIndex < columnDropIndex,
                "Constraint must be dropped before the column");

            // Act - Execute the script to verify it works
            String[] statements = alterScript.split("GO");
            for (String stmt : statements) {
                if (!stmt.trim().isEmpty()) {
                    jdbcTemplate.execute(stmt.trim());
                }
            }

            // Assert - Verify column was dropped and data preserved
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dbo.test_products",
                    Integer.class
            );
            assertEquals(1, count, "Row should still exist");

            // Verify price column is gone
            Integer priceColumnCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM sys.columns c
                    JOIN sys.tables t ON c.object_id = t.object_id
                    WHERE t.name = 'test_products' AND c.name = 'price'
                    """, Integer.class);
            assertEquals(0, priceColumnCount, "Price column should be dropped");

            // Verify stock_quantity column and its constraint still exist
            Integer stockColumnCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM sys.columns c
                    JOIN sys.tables t ON c.object_id = t.object_id
                    WHERE t.name = 'test_products' AND c.name = 'stock_quantity'
                    """, Integer.class);
            assertEquals(1, stockColumnCount, "Stock_quantity column should still exist");
        }

        @Test
        @DisplayName("Should include DEFAULT value when adding new column")
        void shouldIncludeDefaultValueInAddColumn() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_products (
                        product_id INT IDENTITY(1,1) NOT NULL,
                        name NVARCHAR(200) NOT NULL,
                        CONSTRAINT PK_test_products PRIMARY KEY (product_id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_products]
                    (
                        [product_id] INT IDENTITY(1,1) NOT NULL,
                        [name] NVARCHAR(200) NOT NULL,
                        [discount_percent] DECIMAL(5,2) NULL DEFAULT 0,
                        [stock_quantity] INT NOT NULL DEFAULT 100,
                        [is_active] BIT NOT NULL DEFAULT 1,
                        [created_date] DATETIME NOT NULL DEFAULT GETDATE(),
                        CONSTRAINT [PK_test_products] PRIMARY KEY ([product_id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_products", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert
            assertNotNull(alterScript);
            assertTrue(alterScript.contains("ADD [discount_percent] decimal(5,2) NULL DEFAULT 0"),
                "Should include DEFAULT 0 for discount_percent column. Actual script:\n" + alterScript);
            assertTrue(alterScript.contains("ADD [stock_quantity] int NOT NULL DEFAULT 100"),
                "Should include DEFAULT 100 for stock_quantity column. Actual script:\n" + alterScript);
            assertTrue(alterScript.contains("ADD [is_active] bit NOT NULL DEFAULT 1"),
                "Should include DEFAULT 1 for is_active column. Actual script:\n" + alterScript);
            assertTrue(alterScript.contains("ADD [created_date] datetime NOT NULL DEFAULT GETDATE()"),
                "Should include DEFAULT GETDATE() for created_date column. Actual script:\n" + alterScript);
        }

        @Test
        @DisplayName("Should drop DEFAULT constraints before dropping column")
        void shouldDropDefaultConstraintsBeforeDroppingColumn() {
            // Arrange
            jdbcTemplate.execute("""
                    CREATE TABLE dbo.test_users (
                        id INT IDENTITY(1,1) NOT NULL,
                        username NVARCHAR(100) NOT NULL,
                        created_date DATETIME NOT NULL DEFAULT GETDATE(),
                        CONSTRAINT PK_test_users PRIMARY KEY (id)
                    )
                    """);

            String gitDefinition = """
                    CREATE TABLE [dbo].[test_users]
                    (
                        [id] int IDENTITY(1,1) NOT NULL,
                        [username] nvarchar(100) NOT NULL,
                        CONSTRAINT [PK_test_users] PRIMARY KEY ([id])
                    );
                    GO
                    """;

            DbObject tableObject = new DbObject("dbo", "test_users", DbObjectType.TABLE, gitDefinition);

            // Act
            String alterScript = alterScriptGenerator.generateAlterScript(tableObject);

            // Assert and Execute
            assertNotNull(alterScript);
            String[] statements = alterScript.split("GO");
            for (String stmt : statements) {
                if (!stmt.trim().isEmpty()) {
                    jdbcTemplate.execute(stmt.trim());
                }
            }

            // Verify column was dropped
            Integer columnCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM sys.columns c
                    JOIN sys.tables t ON c.object_id = t.object_id
                    WHERE t.name = 'test_users' AND c.name = 'created_date'
                    """, Integer.class);
            assertEquals(0, columnCount, "Created_date column should be dropped");
        }
    }
}

