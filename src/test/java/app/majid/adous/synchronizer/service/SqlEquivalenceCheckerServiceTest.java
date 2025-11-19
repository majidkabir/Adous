package app.majid.adous.synchronizer.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

class SqlEquivalenceCheckerServiceTest {

    @Test
    void viewsWithDifferentQuotingAndWhitespaceShouldBeEquivalent() throws Exception {
        String sqlA = "SET ANSI_NULLS OFF; GO SET QUOTED_IDENTIFIER OFF; GO  CREATE   VIEW [BI].[V_SKU]  AS SELECT *  FROM [SKU] WITH (NOLOCK)  GO";
        String sqlB = "SET ANSI_NULLS OFF; GO SET QUOTED_IDENTIFIER OFF; GO  CREATE VIEW BI.V_SKU AS      SELECT *     FROM [SKU] WITH (NOLOCK)    GO";

        SqlEquivalenceCheckerService service = new SqlEquivalenceCheckerService();
        // Inject default schema expected by service (property normally provided by Spring)
        Field f = SqlEquivalenceCheckerService.class.getDeclaredField("defaultSchema");
        f.setAccessible(true);
        f.set(service, "dbo");

        assertTrue(service.equals(sqlA, sqlB), "Expected the two CREATE VIEW statements to be normalized as equivalent");
    }
}
