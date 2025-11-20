package app.majid.adous.synchronizer.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = {
                SqlEquivalenceCheckerService.class,
                SqlEquivalenceCheckerServiceTest.CacheConfig.class
        },
        properties = {
                "spring.application.database.default-schema=dbo"   // <-- whatever your @Value key is
        }
)
class SqlEquivalenceCheckerServiceTest {

    @Autowired
    SqlEquivalenceCheckerService service;

    @MockitoSpyBean
    SqlEquivalenceCheckerService spyService;

    @Test
    void viewsWithDifferentQuotingAndWhitespaceShouldBeEquivalent() throws Exception {
        String sqlA = "CREATE   VIEW [BI].[V_SKU]  AS SELECT *  FROM [SKU] WITH (NOLOCK)  GO";
        String sqlB = "CREATE VIEW BI.V_SKU AS      SELECT *     FROM [SKU] WITH (NOLOCK)    GO";

        assertTrue(service.equals(sqlA, sqlB), "Expected the two CREATE VIEW statements to be normalized as equivalent");
    }

    @Test
    void viewsWithDefaultSchemaInSourceTableShouldBeEquivalent() throws Exception {
        String sqlA = "CREATE    VIEW [BI].[V_ChannelInv] AS  \nSELECT *\nFROM dbo.V_ChannelInv with (nolock)\nGO";
        String sqlB = "/***/\nCREATE   VIEW [BI].[V_ChannelInv]\nAS\nSELECT * FROM V_ChannelInv WITH (NOLOCK)\nGO";

        assertTrue(service.equals(sqlA, sqlB), "Expected views to be equivalent: dbo.V_ChannelInv vs V_ChannelInv (dbo is default schema)");
    }

    @Test
    void normalizingSameSqlShouldBeReturnedFromCache() throws Exception {
        String sqlA = "CREATE    VIEW [BI].[V_ChannelInv] AS  \nSELECT *\nFROM dbo.V_ChannelInv with (nolock)\nGO";
        String sqlB = "/***/\nCREATE   VIEW [BI].[V_ChannelInv]\nAS\nSELECT * FROM V_ChannelInv WITH (NOLOCK)\nGO";

        service.equals(sqlA, sqlB);
        Mockito.verify(spyService, Mockito.times(2))
                .normalizeSql(Mockito.anyString());
        service.equals(sqlA, sqlB);
        Mockito.verify(spyService, Mockito.times(2))
                .normalizeSql(Mockito.anyString());
    }


    @TestConfiguration
    @EnableCaching
    static class CacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("sqlNormalizationCache");
        }
    }
}

