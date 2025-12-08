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

import static org.junit.jupiter.api.Assertions.*;

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
    void tablesWithDifferentColumnOrderShouldBeEquivalent() {
        String sqlA = "CREATE TABLE Products (ProductID INT, Name VARCHAR(100), Price DECIMAL(10,2));";
        String sqlB = "CREATE TABLE Products (Name VARCHAR(100), ProductID INT, Price DECIMAL(10,2));";

        String normalizedA = service.normalizeSql(sqlA);
        String normalizedB = service.normalizeSql(sqlB);

        assertEquals(normalizedA, normalizedB,
            "Tables with same columns in different order should be equivalent after column sorting");
    }

    @Test
    void tablesWithIndexesInDifferentOrderShouldBeEquivalent() {
        String sqlA = """
                CREATE TABLE Products (ProductID INT PRIMARY KEY, Category VARCHAR(50), Price DECIMAL(10,2));
                GO
                CREATE INDEX idx_Products_Category ON Products(Category);
                GO
                CREATE INDEX idx_Products_Price ON Products(Price);
                GO
                """;

        String sqlB = """
                CREATE TABLE Products (ProductID INT PRIMARY KEY, Category VARCHAR(50), Price DECIMAL(10,2));
                GO
                CREATE INDEX idx_Products_Price ON Products(Price);
                GO
                CREATE INDEX idx_Products_Category ON Products(Category);
                GO
                """;

        String normalizedA = service.normalizeSql(sqlA);
        String normalizedB = service.normalizeSql(sqlB);

        assertEquals(normalizedA, normalizedB,
                "Tables with same indexes in different order should be equivalent after index sorting");
    }

    @Test
    void tablesWithBothColumnAndIndexReorderingShouldBeEquivalent() {
        String sqlA = """
                CREATE TABLE Products (ProductID INT, Name VARCHAR(100), Price DECIMAL(10,2));
                GO
                CREATE INDEX idx_Name ON Products(Name);
                GO
                CREATE INDEX idx_Price ON Products(Price);
                GO
                """;

        String sqlB = """
                CREATE TABLE Products (Price DECIMAL(10,2), ProductID INT, Name VARCHAR(100));
                GO
                CREATE INDEX idx_Price ON Products(Price);
                GO
                CREATE INDEX idx_Name ON Products(Name);
                GO
                """;

        String normalizedA = service.normalizeSql(sqlA);
        String normalizedB = service.normalizeSql(sqlB);

        assertEquals(normalizedA, normalizedB,
                "Tables with both columns and indexes in different order should be equivalent");
    }

    @Test
    void viewsWithDifferentQuotingAndWhitespaceShouldBeEquivalent() {
        String sqlA = "CREATE   VIEW [BI].[V_SKU]  AS SELECT *  FROM [SKU] WITH (NOLOCK)  GO";
        String sqlB = "CREATE VIEW BI.V_SKU AS      SELECT *     FROM [SKU] WITH (NOLOCK)    GO";

        String normalizedA = service.normalizeSql(sqlA);
        String normalizedB = service.normalizeSql(sqlB);

        assertEquals(normalizedA, normalizedB,
                "Expected the two CREATE VIEW statements to be normalized as equivalent");
    }

    @Test
    void viewsWithDefaultSchemaInSourceTableShouldBeEquivalent() {
        String sqlA = "CREATE    VIEW [BI].[V_ChannelInv] AS  \nSELECT *\nFROM dbo.V_ChannelInv with (nolock)\nGO";
        String sqlB = "/***/\nCREATE   VIEW [BI].[V_ChannelInv]\nAS\nSELECT * FROM V_ChannelInv WITH (NOLOCK)\nGO";

        String normalizedA = service.normalizeSql(sqlA);
        String normalizedB = service.normalizeSql(sqlB);

        assertEquals(normalizedA, normalizedB,
                "Expected views to be equivalent: dbo.V_ChannelInv vs V_ChannelInv (dbo is default schema)");
    }

    @Test
    void normalizingSameSqlShouldBeReturnedFromCache() {
        String sqlA = "CREATE VIEW [BI].[V_ChannelInv] AS \nSELECT *\nFROM dbo.V_ChannelInv with (nolock)\nGO";
        String sqlB = "CREATE VIEW [dbo].[V_ChannelInv] AS \nSELECT *\nFROM dbo.V_ChannelInv with (nolock)\nGO";

        service.equals(sqlA, sqlB);
        Mockito.verify(spyService, Mockito.times(2))
                .normalizeSql(Mockito.anyString());

        service.equals(sqlA, sqlA);
        Mockito.verify(spyService, Mockito.times(2))
                .normalizeSql(Mockito.anyString());
    }

    @Test
    void createTableWithDifferentDefaultFormatsShouldBeEquivalent() {
        String sqlA = """
                CREATE TABLE [dbo].[Products]
                (
                    [ProductID] INT IDENTITY(1,1) NOT NULL,
                    [Name] VARCHAR(200) NOT NULL,
                    [StockQuantity] INT NOT NULL DEFAULT 0,
                    [IsActive] BIT NOT NULL DEFAULT 1,
                    [CreatedDate] DATETIME NOT NULL DEFAULT GETDATE(),
                    CONSTRAINT [PK_Products] PRIMARY KEY ([ProductID])
                );
                GO
                """;

        String sqlB = """
                CREATE TABLE [dbo].[products]
                (
                    [ProductID] int IDENTITY(1,1) NOT NULL,
                    [Name] varchar(200) NOT NULL,
                    [StockQuantity] int NOT NULL DEFAULT ((0)),
                    [IsActive] bit NOT NULL DEFAULT ((1)),
                    [CreatedDate] datetime NOT NULL DEFAULT (getdate()),
                    CONSTRAINT [PK_Products] PRIMARY KEY ([ProductID])
                );
                GO
                """;

        String normalizeSqlA = service.normalizeSql(sqlA);
        String normalizeSqlB = service.normalizeSql(sqlB);

        assertEquals(normalizeSqlA, normalizeSqlB,
                "Create table statements with different default value formats should be equivalent");
    }

    @Test
    void createTableWithCheckConstraintVariationsShouldBeEquivalent() {
        String sqlA = """
                CREATE TABLE [dbo].[Products]
                (
                    [ProductID] INT IDENTITY(1,1) NOT NULL,
                    [Price] DECIMAL(10,2) NOT NULL,
                    [DiscountPercent] DECIMAL(5,2) NULL,
                    CONSTRAINT [PK_Products] PRIMARY KEY ([ProductID]),
                    CONSTRAINT [CK_Products_Price] CHECK ([Price] >= 0),
                    CONSTRAINT [CK_Products_Discount] CHECK ([DiscountPercent] >= 0 AND [DiscountPercent] <= 100)
                );
                GO
                """;

        String sqlB = """
                CREATE TABLE [dbo].[products]
                (
                    [ProductID] int IDENTITY(1,1) NOT NULL,
                    [Price] decimal(10, 2) NOT NULL,
                    [DiscountPercent] decimal(5, 2) NULL,
                    CONSTRAINT [PK_Products] PRIMARY KEY ([ProductID]),
                    CONSTRAINT [CK_Products_Price] CHECK ([Price]>=(0)),
                    CONSTRAINT [CK_Products_Discount] CHECK ([DiscountPercent]>=(0) AND [DiscountPercent]<=(100))
                );
                GO
                """;

        String normalizeSqlA = service.normalizeSql(sqlA);
        String normalizeSqlB = service.normalizeSql(sqlB);

        assertEquals(normalizeSqlA, normalizeSqlB,
                "Create table statements with different check constraint formats should be equivalent");
    }

    @Test
    void createTableWithIndexVariationsShouldBeEquivalent() {
        String sqlA = """
                CREATE TABLE [dbo].[Products] ([ProductID] INT PRIMARY KEY);
                GO
                CREATE INDEX [idx_Products_Category] ON [dbo].[Products]([Category]) WHERE [IsActive] = 1;
                GO
                CREATE INDEX [idx_Products_Discount] ON [dbo].[Products]([DiscountPercent]) WHERE [DiscountPercent] > 0;
                GO
                """;

        String sqlB = """
                CREATE TABLE [dbo].[products] ([ProductID] int PRIMARY KEY);
                GO
                CREATE NONCLUSTERED INDEX [idx_Products_Category] ON [dbo].[products] ([Category]) WHERE [IsActive] = 1;
                GO
                CREATE NONCLUSTERED INDEX [idx_Products_Discount] ON [dbo].[products] ([DiscountPercent]) WHERE [DiscountPercent] > 0;
                GO
                """;

        String normalizeSqlA = service.normalizeSql(sqlA);
        String normalizeSqlB = service.normalizeSql(sqlB);

        assertEquals(normalizeSqlA, normalizeSqlB,
                "Create table statements with different index definitions should be equivalent");
    }

    @Test
    void completeProductsTableFromGitAndDbShouldBeEquivalent() {
        String sqlA = """
                CREATE TABLE [dbo].[Products]
                (
                    [ProductID] INT IDENTITY(1,1) NOT NULL,
                    [Name] VARCHAR(200) NOT NULL,
                    [Description] VARCHAR(MAX) NULL,
                    [Category] VARCHAR(50) NOT NULL,
                    [Price] DECIMAL(10,2) NOT NULL,
                    [DiscountPercent] DECIMAL(5,2) NULL DEFAULT 0,
                    [StockQuantity] INT NOT NULL DEFAULT 0,
                    [IsActive] BIT NOT NULL DEFAULT 1,
                    [CreatedDate] DATETIME NOT NULL DEFAULT GETDATE(),
                    CONSTRAINT [PK_Products] PRIMARY KEY ([ProductID]),
                    CONSTRAINT [CK_Products_Price] CHECK ([Price] >= 0),
                    CONSTRAINT [CK_Products_Stock] CHECK ([StockQuantity] >= 0),
                    CONSTRAINT [CK_Products_Discount] CHECK ([DiscountPercent] >= 0 AND [DiscountPercent] <= 100)
                );
                GO

                CREATE INDEX [idx_Products_Category] ON [dbo].[Products]([Category]) WHERE [IsActive] = 1;
                GO

                CREATE INDEX [idx_Products_Discount] ON [dbo].[Products]([DiscountPercent]) WHERE [DiscountPercent] > 0;
                GO
                """;

        String sqlB = """
                CREATE TABLE [dbo].[products]
                (
                    [ProductID] int IDENTITY(1,1) NOT NULL,
                    [Name] varchar(200) NOT NULL,
                    [Description] varchar(MAX) NULL,
                    [Category] varchar(50) NOT NULL,
                    [Price] decimal(10, 2) NOT NULL,
                    [StockQuantity] int NOT NULL DEFAULT ((0)),
                    [IsActive] bit NOT NULL DEFAULT ((1)),
                    [CreatedDate] datetime NOT NULL DEFAULT (getdate()),
                    [DiscountPercent] decimal(5, 2) NULL DEFAULT 0,
                    CONSTRAINT [PK_Products] PRIMARY KEY ([ProductID]),
                    CONSTRAINT [CK_Products_Price] CHECK ([Price]>=(0)),
                    CONSTRAINT [CK_Products_Stock] CHECK ([StockQuantity]>=(0)),
                    CONSTRAINT [CK_Products_Discount] CHECK ([DiscountPercent]>=(0) AND [DiscountPercent]<=(100))
                );
                GO

                CREATE NONCLUSTERED INDEX [idx_Products_Category] ON [dbo].[products] ([Category]) WHERE [IsActive] = 1;
                GO
                CREATE NONCLUSTERED INDEX [idx_Products_Discount] ON [dbo].[products] ([DiscountPercent]) WHERE [DiscountPercent] > 0;
                GO
                """;

        String normalizeSqlA = service.normalizeSql(sqlA);
        String normalizeSqlB = service.normalizeSql(sqlB);

        assertEquals(normalizeSqlA, normalizeSqlB,
                "Complete Products table definitions from Git and DB should be equivalent after normalization");
    }

    @Test
    void shouldExtractBothTableAndIndexStatements() {
        String sqlWithBoth = """
                CREATE TABLE [dbo].[orders]
                (
                    [OrderID] int IDENTITY(1,1) NOT NULL,
                    [Status] varchar(20) NOT NULL
                );
                GO
                CREATE INDEX [idx_Orders_Status] ON [dbo].[orders] ([Status]);
                GO
                """;

        String result = service.normalizeSql(sqlWithBoth);

        assertTrue(result.toLowerCase().contains("create table"), "Expected CREATE TABLE statement to be preserved");
        assertTrue(result.toLowerCase().contains("create index"), "Expected CREATE INDEX statement to be preserved");
    }

    @Test
    void shouldPreserveCreateIndexStatement() {
        String sqlWithIndex = """
                CREATE TABLE [dbo].[orders] ([ID] int);
                GO
                CREATE INDEX [idx_test] ON [dbo].[orders] ([ID]);
                GO
                """;

        String result = service.normalizeSql(sqlWithIndex);

        assertTrue(result.toLowerCase().contains("idx_test"), "Expected index name to be preserved");
        assertTrue(result.toLowerCase().contains("create index"), "Expected CREATE INDEX statement to be preserved");
    }

    @Test
    void shouldHandleMultipleCreateStatementsSeparatedByGO() {
        String sqlMultiple = """
                CREATE TABLE [dbo].[table1] ([ID] int);
                GO
                CREATE INDEX [idx1] ON [dbo].[table1] ([ID]);
                GO
                CREATE INDEX [idx2] ON [dbo].[table1] ([ID]);
                GO
                """;

        String result = service.normalizeSql(sqlMultiple);

        assertTrue(result.toLowerCase().contains("create table"), "Expected CREATE TABLE statement to be preserved");
        assertTrue(result.toLowerCase().contains("idx1"), "Expected first index name to be preserved");
        assertTrue(result.toLowerCase().contains("idx2"), "Expected second index name to be preserved");
    }

    @Test
    void shouldNormalizeDecimalDataTypeSpacing() {
        String sqlDecimalSpace = "CREATE TABLE [t] ([Price] DECIMAL(10, 2))";
        String sqlDecimalNoSpace = "CREATE TABLE [t] ([Price] DECIMAL(10,2))";

        String normalizedA = service.normalizeSql(sqlDecimalSpace);
        String normalizedB = service.normalizeSql(sqlDecimalNoSpace);

        assertEquals(normalizedA, normalizedB,
                "Decimal data types with or without space after comma should normalize equivalently");
    }

    @Test
    void shouldNormalizeVarcharWithSpaceBeforeParenthesis() {
        String sqlWithSpace = "CREATE TABLE [t] ([Name] VARCHAR (200))";
        String sqlNoSpace = "CREATE TABLE [t] ([Name] VARCHAR(200))";

        String normalizedA = service.normalizeSql(sqlWithSpace);
        String normalizedB = service.normalizeSql(sqlNoSpace);

        assertEquals(normalizedA, normalizedB,
                "Varchar data types with or without space before parenthesis should normalize equivalently");
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

