package app.majid.adous.git.mapper;

import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DbObjectMapper Tests")
class DbObjectMapperTest {

    private DbObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new DbObjectMapper();
    }

    @Nested
    @DisplayName("Valid Path Mapping Tests")
    class ValidPathMappingTests {

        @Test
        @DisplayName("Should map stored procedure path correctly")
        void shouldMapStoredProcedurePath() {
            // Arrange
            String path = "base/PROCEDURE/dbo/sp_GetUsers.sql";
            String definition = "CREATE PROCEDURE dbo.sp_GetUsers AS BEGIN SELECT * FROM Users END";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertNotNull(result);
            assertEquals("dbo", result.schema());
            assertEquals("sp_GetUsers", result.name());
            assertEquals(DbObjectType.PROCEDURE, result.type());
            assertEquals(definition, result.definition());
        }

        @Test
        @DisplayName("Should map function path correctly")
        void shouldMapFunctionPath() {
            // Arrange
            String path = "base/FUNCTION/dbo/fn_Calculate.sql";
            String definition = "CREATE FUNCTION dbo.fn_Calculate() RETURNS INT AS BEGIN RETURN 1 END";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("fn_Calculate", result.name());
            assertEquals(DbObjectType.FUNCTION, result.type());
            assertEquals(definition, result.definition());
        }

        @Test
        @DisplayName("Should map view path correctly")
        void shouldMapViewPath() {
            // Arrange
            String path = "base/VIEW/dbo/vw_UserReport.sql";
            String definition = "CREATE VIEW dbo.vw_UserReport AS SELECT * FROM Users";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("vw_UserReport", result.name());
            assertEquals(DbObjectType.VIEW, result.type());
            assertEquals(definition, result.definition());
        }

        @Test
        @DisplayName("Should map trigger path correctly")
        void shouldMapTriggerPath() {
            // Arrange
            String path = "base/TRIGGER/dbo/tr_UserAudit.sql";
            String definition = "CREATE TRIGGER dbo.tr_UserAudit ON Users FOR INSERT AS BEGIN END";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("tr_UserAudit", result.name());
            assertEquals(DbObjectType.TRIGGER, result.type());
            assertEquals(definition, result.definition());
        }

        @Test
        @DisplayName("Should map synonym path correctly")
        void shouldMapSynonymPath() {
            // Arrange
            String path = "base/SYNONYM/dbo/syn_RemoteTable.sql";
            String definition = "CREATE SYNONYM [dbo].[syn_RemoteTable] FOR [RemoteServer].[RemoteDB].[dbo].[Table1];\nGO";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("syn_RemoteTable", result.name());
            assertEquals(DbObjectType.SYNONYM, result.type());
            assertEquals(definition, result.definition());
        }

        @Test
        @DisplayName("Should map table type path correctly")
        void shouldMapTableTypePath() {
            // Arrange
            String path = "base/TABLE_TYPE/dbo/tt_UserList.sql";
            String definition = "CREATE TYPE [dbo].[tt_UserList] AS TABLE\n(\n    [UserId] int NOT NULL,\n    [UserName] nvarchar(100) NOT NULL\n);\nGO";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("tt_UserList", result.name());
            assertEquals(DbObjectType.TABLE_TYPE, result.type());
            assertEquals(definition, result.definition());
        }

        @Test
        @DisplayName("Should map table path correctly")
        void shouldMapTablePath() {
            // Arrange
            String path = "base/TABLE/dbo/users.sql";
            String definition = "CREATE TABLE [dbo].[users]\n(\n    [id] int IDENTITY(1,1) NOT NULL,\n    [username] nvarchar(100) NOT NULL,\n    CONSTRAINT [PK_users] PRIMARY KEY ([id])\n);\nGO";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("users", result.name());
            assertEquals(DbObjectType.TABLE, result.type());
            assertEquals(definition, result.definition());
        }

        @Test
        @DisplayName("Should handle null definition")
        void shouldHandleNullDefinition() {
            // Arrange
            String path = "base/PROCEDURE/dbo/sp_Deleted.sql";

            // Act
            DbObject result = mapper.fromPath(path, null);

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("sp_Deleted", result.name());
            assertEquals(DbObjectType.PROCEDURE, result.type());
            assertNull(result.definition());
        }

        @Test
        @DisplayName("Should handle empty definition")
        void shouldHandleEmptyDefinition() {
            // Arrange
            String path = "base/PROCEDURE/dbo/sp_Empty.sql";

            // Act
            DbObject result = mapper.fromPath(path, "");

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("sp_Empty", result.name());
            assertEquals(DbObjectType.PROCEDURE, result.type());
            assertEquals("", result.definition());
        }
    }

    @Nested
    @DisplayName("Different Schema Tests")
    class DifferentSchemaTests {

        @Test
        @DisplayName("Should handle custom schema name")
        void shouldHandleCustomSchemaName() {
            // Arrange
            String path = "base/PROCEDURE/custom_schema/sp_Custom.sql";
            String definition = "CREATE PROCEDURE custom_schema.sp_Custom AS BEGIN END";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("custom_schema", result.schema());
            assertEquals("sp_Custom", result.name());
        }
    }

    @Nested
    @DisplayName("Diff Root Path Tests")
    class DifferentRootPathTests {

        @Test
        @DisplayName("Should handle diff path")
        void shouldHandleDiffPath() {
            // Arrange
            String path = "diff/testDb/PROCEDURE/dbo/sp_Test.sql";
            String definition = "CREATE PROCEDURE dbo.sp_Test AS BEGIN END";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("sp_Test", result.name());
            assertEquals(DbObjectType.PROCEDURE, result.type());
        }

        @Test
        @DisplayName("Should handle nested diff path")
        void shouldHandleNestedDiffPath() {
            // Arrange
            String path = "repository/diff/db1/FUNCTION/schema1/fn_Test.sql";
            String definition = "CREATE FUNCTION schema1.fn_Test() RETURNS INT AS BEGIN RETURN 1 END";

            // Act
            DbObject result = mapper.fromPath(path, definition);

            // Assert
            assertEquals("schema1", result.schema());
            assertEquals("fn_Test", result.name());
            assertEquals(DbObjectType.FUNCTION, result.type());
        }
    }

    @Nested
    @DisplayName("File Name Tests")
    class FileNameTests {

        @Test
        @DisplayName("Should handle object name with underscores")
        void shouldHandleObjectNameWithUnderscores() {
            // Arrange
            String path = "base/PROCEDURE/dbo/sp_Get_All_Users.sql";

            // Act
            DbObject result = mapper.fromPath(path, "definition");

            // Assert
            assertEquals("sp_Get_All_Users", result.name());
        }

        @Test
        @DisplayName("Should handle object name with dots")
        void shouldHandleObjectNameWithDots() {
            // Arrange
            String path = "base/PROCEDURE/dbo/sp.test.name.sql";

            // Act
            DbObject result = mapper.fromPath(path, "definition");

            // Assert
            assertEquals("sp.test.name", result.name());
        }

        @Test
        @DisplayName("Should handle object name with numbers")
        void shouldHandleObjectNameWithNumbers() {
            // Arrange
            String path = "base/PROCEDURE/dbo/sp_GetUser123.sql";

            // Act
            DbObject result = mapper.fromPath(path, "definition");

            // Assert
            assertEquals("sp_GetUser123", result.name());
        }
    }

    @Nested
    @DisplayName("Invalid Path Tests")
    class InvalidPathTests {

        @Test
        @DisplayName("Should throw exception for non-SQL file")
        void shouldThrowExceptionForNonSqlFile() {
            // Arrange
            String path = "base/PROCEDURE/dbo/sp_Test.txt";

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> mapper.fromPath(path, "definition")
            );
            assertEquals("Invalid file type: " + path, exception.getMessage());
        }

        @Test
        @DisplayName("Should throw exception for path without file extension")
        void shouldThrowExceptionForPathWithoutExtension() {
            // Arrange
            String path = "base/PROCEDURE/dbo/sp_Test";

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> mapper.fromPath(path, "definition")
            );
            assertEquals("Invalid file type: " + path, exception.getMessage());
        }

        @Test
        @DisplayName("Should throw exception for path with insufficient depth")
        void shouldThrowExceptionForInsufficientDepth() {
            // Arrange
            String path = "dbo/sp_Test.sql";

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> mapper.fromPath(path, "definition")
            );
            assertEquals("Invalid path: " + path, exception.getMessage());
        }

        @Test
        @DisplayName("Should throw exception for path with only two levels")
        void shouldThrowExceptionForTwoLevels() {
            // Arrange
            String path = "PROCEDURE/dbo/sp_Test.sql";

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> mapper.fromPath(path, "definition")
            );
            assertEquals("Invalid path: " + path, exception.getMessage());
        }

        @Test
        @DisplayName("Should throw exception for invalid object type")
        void shouldThrowExceptionForInvalidObjectType() {
            // Arrange
            String path = "base/INVALID_TYPE/dbo/sp_Test.sql";

            // Act & Assert
            assertThrows(
                    IllegalArgumentException.class,
                    () -> mapper.fromPath(path, "definition")
            );
        }
    }

    @Nested
    @DisplayName("Type Conversion Tests")
    class TypeConversionTests {

        @Test
        @DisplayName("Should convert lowercase type to uppercase enum")
        void shouldConvertLowercaseTypeToUppercase() {
            // Arrange
            String path = "base/procedure/dbo/sp_Test.sql";

            // Act
            DbObject result = mapper.fromPath(path, "definition");

            // Assert
            assertEquals(DbObjectType.PROCEDURE, result.type());
        }

        @Test
        @DisplayName("Should convert mixed case type to uppercase enum")
        void shouldConvertMixedCaseTypeToUppercase() {
            // Arrange
            String path = "base/FuNcTiOn/dbo/fn_Test.sql";

            // Act
            DbObject result = mapper.fromPath(path, "definition");

            // Assert
            assertEquals(DbObjectType.FUNCTION, result.type());
        }

        @Test
        @DisplayName("Should handle all DbObjectType enum values")
        void shouldHandleAllDbObjectTypeEnumValues() {
            // Test each enum value
            for (DbObjectType type : DbObjectType.values()) {
                String path = String.format("base/%s/dbo/test.sql", type.name());
                DbObject result = mapper.fromPath(path, "definition");
                assertEquals(type, result.type());
            }
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle very long path")
        void shouldHandleVeryLongPath() {
            // Arrange
            String path = "very/long/nested/base/PROCEDURE/dbo/sp_VeryLongNameForTesting.sql";

            // Act
            DbObject result = mapper.fromPath(path, "definition");

            // Assert
            assertEquals("dbo", result.schema());
            assertEquals("sp_VeryLongNameForTesting", result.name());
            assertEquals(DbObjectType.PROCEDURE, result.type());
        }

        @Test
        @DisplayName("Should handle path with Windows-style separators")
        void shouldHandleWindowsStyleSeparators() {
            // Note: This test demonstrates current behavior - may need adjustment based on requirements
            String path = "base\\PROCEDURE\\dbo\\sp_Test.sql";

            // This might throw an exception or behave differently depending on OS
            // The test documents the current behavior
            assertThrows(IllegalArgumentException.class, () -> mapper.fromPath(path, "definition"));
        }
    }
}

