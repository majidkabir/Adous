package app.majid.adous.db.service;

import app.majid.adous.db.service.ModuleDefinitionRewriter.FunctionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ModuleDefinitionRewriterTest {

    private static final String SET_PREFIX = "SET ANSI_NULLS ON;\r\nGO\r\nSET QUOTED_IDENTIFIER ON;\r\nGO\r\n";

    static Stream<Arguments> createOrAlterCases() {
        return Stream.of(
                Arguments.of("CREATE PROCEDURE dbo.p AS SELECT 1",
                        "CREATE OR ALTER PROCEDURE dbo.p AS SELECT 1"),
                Arguments.of("create proc dbo.p as select 1",
                        "create OR ALTER proc dbo.p as select 1"),
                Arguments.of(SET_PREFIX + "CREATE VIEW dbo.v AS SELECT 1 AS x\r\nGO",
                        SET_PREFIX + "CREATE OR ALTER VIEW dbo.v AS SELECT 1 AS x\r\nGO"),
                Arguments.of("-- create procedure to do things\nCREATE PROCEDURE dbo.p AS SELECT 1",
                        "-- create procedure to do things\nCREATE OR ALTER PROCEDURE dbo.p AS SELECT 1"),
                Arguments.of("/* outer /* CREATE VIEW nested */ still comment */\nCREATE VIEW dbo.v AS SELECT 1 AS x",
                        "/* outer /* CREATE VIEW nested */ still comment */\nCREATE OR ALTER VIEW dbo.v AS SELECT 1 AS x"),
                Arguments.of("CREATE TRIGGER dbo.t ON dbo.tbl AFTER INSERT AS BEGIN CREATE TABLE #x (i int) END",
                        "CREATE OR ALTER TRIGGER dbo.t ON dbo.tbl AFTER INSERT AS BEGIN CREATE TABLE #x (i int) END"),
                Arguments.of("CREATE FUNCTION [dbo].[f]() RETURNS int AS BEGIN RETURN 1 END",
                        "CREATE OR ALTER FUNCTION [dbo].[f]() RETURNS int AS BEGIN RETURN 1 END"),
                Arguments.of("CREATE OR ALTER PROCEDURE dbo.p AS SELECT 1",
                        "CREATE OR ALTER PROCEDURE dbo.p AS SELECT 1")
        );
    }

    @ParameterizedTest
    @MethodSource("createOrAlterCases")
    void shouldRewriteModuleCreateToCreateOrAlter(String input, String expected) {
        assertEquals(Optional.of(expected), ModuleDefinitionRewriter.toCreateOrAlter(input));
    }

    @Test
    void shouldReturnEmptyWhenNoModuleCreateStatement() {
        assertTrue(ModuleDefinitionRewriter.toCreateOrAlter("CREATE SYNONYM dbo.s FOR dbo.t").isEmpty());
        assertTrue(ModuleDefinitionRewriter.toCreateOrAlter("-- CREATE PROCEDURE only in a comment").isEmpty());
        assertTrue(ModuleDefinitionRewriter.toCreateOrAlter("SELECT 'CREATE PROCEDURE x'").isEmpty());
    }

    @ParameterizedTest
    @MethodSource("createOrAlterCases")
    void shouldRestoreCanonicalCreateWhenReadBack(String original, String rewritten) {
        String canonical = ModuleDefinitionRewriter.toCanonicalCreate(rewritten);
        String expected = original.contains("OR ALTER")
                ? original.replace("CREATE OR ALTER", "CREATE")
                : original;
        assertEquals(expected, canonical);
    }

    @Test
    void shouldCollapseBlanksLeftBySqlServerAfterCreateOrAlter() {
        // SQL Server stores "CREATE OR ALTER PROCEDURE" as "CREATE   PROCEDURE"
        assertEquals(SET_PREFIX + "CREATE PROCEDURE dbo.p AS SELECT 1",
                ModuleDefinitionRewriter.toCanonicalCreate(SET_PREFIX + "CREATE   PROCEDURE dbo.p AS SELECT 1"));
        // a line break between the keywords is the author's formatting and is kept
        assertEquals("CREATE\nVIEW dbo.v AS SELECT 1 AS x",
                ModuleDefinitionRewriter.toCanonicalCreate("CREATE\nVIEW dbo.v AS SELECT 1 AS x"));
    }

    @Test
    void shouldLeaveCanonicalDefinitionsUnchanged() {
        String sql = SET_PREFIX + "CREATE PROCEDURE dbo.p AS SELECT 'CREATE OR ALTER' AS s";
        assertEquals(sql, ModuleDefinitionRewriter.toCanonicalCreate(sql));
        assertNull(ModuleDefinitionRewriter.toCanonicalCreate(null));
    }

    static Stream<Arguments> functionKindCases() {
        return Stream.of(
                Arguments.of("CREATE FUNCTION dbo.f(@a int) RETURNS int AS BEGIN RETURN @a END",
                        FunctionKind.SCALAR),
                Arguments.of("CREATE FUNCTION dbo.f() RETURNS TABLE AS RETURN SELECT 1 AS x",
                        FunctionKind.INLINE_TABLE),
                Arguments.of("CREATE FUNCTION dbo.f() RETURNS @t TABLE (x int) AS BEGIN INSERT @t VALUES (1) RETURN END",
                        FunctionKind.MULTI_STATEMENT_TABLE),
                Arguments.of("-- RETURNS TABLE in a comment\nCREATE FUNCTION dbo.f() RETURNS nvarchar(10) AS BEGIN RETURN N'x' END",
                        FunctionKind.SCALAR)
        );
    }

    @ParameterizedTest
    @MethodSource("functionKindCases")
    void shouldDetectFunctionKind(String sql, FunctionKind expected) {
        assertEquals(Optional.of(expected), ModuleDefinitionRewriter.functionKind(sql));
    }
}
