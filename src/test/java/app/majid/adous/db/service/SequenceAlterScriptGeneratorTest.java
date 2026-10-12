package app.majid.adous.db.service;

import app.majid.adous.db.service.SequenceAlterScriptGenerator.SequenceDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SequenceAlterScriptGeneratorTest {

    @Test
    void shouldParseDefinitionGeneratedByAdous() {
        SequenceDefinition def = SequenceAlterScriptGenerator.parse("""
                    CREATE SEQUENCE [dbo].[seq_orderid]
                        AS int
                        START WITH 1
                        INCREMENT BY 2
                        MINVALUE 1
                        MAXVALUE 1000
                        NO CYCLE
                        NO CACHE;
                    GO
                """).orElseThrow();

        assertEquals(new SequenceDefinition("int", null, 1L, 2L, 1L, 1000L, false, "NO CACHE"), def);
    }

    @Test
    void shouldParseCycleCacheSizeAndNegativeValues() {
        SequenceDefinition def = SequenceAlterScriptGenerator.parse(
                "CREATE SEQUENCE dbo.s AS BIGINT START WITH -1 INCREMENT BY -1 MINVALUE -100 MAXVALUE -1 CYCLE CACHE 20")
                .orElseThrow();

        assertEquals(new SequenceDefinition("bigint", null, -1L, -1L, -100L, -1L, true, "CACHE 20"), def);
    }

    @Test
    void shouldUseSqlServerDefaultsForOmittedOptions() {
        SequenceDefinition def = SequenceAlterScriptGenerator.parse("CREATE SEQUENCE dbo.s").orElseThrow();

        assertEquals(new SequenceDefinition("bigint", null, null, null, null, null, false, "CACHE"), def);
    }

    @Test
    void shouldParseDecimalPrecisionAndNoMinMax() {
        SequenceDefinition def = SequenceAlterScriptGenerator.parse(
                "CREATE SEQUENCE dbo.s AS decimal(10, 0) NO MINVALUE NO MAXVALUE CACHE").orElseThrow();

        assertEquals("decimal", def.dataType());
        assertEquals(10, def.precision());
        assertNull(def.minValue());
        assertNull(def.maxValue());
        assertEquals("CACHE", def.cache());
    }

    @Test
    void shouldIgnoreOptionsInComments() {
        SequenceDefinition def = SequenceAlterScriptGenerator.parse("""
                -- was INCREMENT BY 5 and CYCLE before
                CREATE SEQUENCE dbo.s AS int /* START WITH 9 */ START WITH 1 INCREMENT BY 1 NO CYCLE
                """).orElseThrow();

        assertEquals(1L, def.startWith());
        assertEquals(1L, def.incrementBy());
        assertFalse(def.cycle());
    }

    @Test
    void shouldReturnEmptyForNonSequenceDefinition() {
        assertTrue(SequenceAlterScriptGenerator.parse("CREATE VIEW dbo.v AS SELECT 1 AS x").isEmpty());
        assertTrue(SequenceAlterScriptGenerator.parse(null).isEmpty());
    }
}
