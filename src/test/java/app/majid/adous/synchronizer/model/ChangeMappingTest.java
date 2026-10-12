package app.majid.adous.synchronizer.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChangeMappingTest {

    @Test
    void shouldMapRepoObjectsToFileChanges() {
        assertEquals(new RepoFileChange("base/VIEW/sales/v_orders.sql", DbObjectType.VIEW, "sales", "v_orders",
                        RepoFileChange.Action.WRITE),
                RepoFileChange.from(new RepoObject("base/VIEW/sales/v_orders.sql", "CREATE VIEW sales.v_orders AS SELECT 1 AS x")));
        assertEquals(RepoFileChange.Action.DELETE,
                RepoFileChange.from(new RepoObject("diff/p/db1/VIEW/dbo/v.sql", null)).action());
        assertEquals(RepoFileChange.Action.MARK_ABSENT,
                RepoFileChange.from(new RepoObject("diff/p/db1/VIEW/dbo/v.sql", "")).action());
    }

    @Test
    void shouldKeepPathWhenItDoesNotFollowTheLayout() {
        RepoFileChange change = RepoFileChange.from(new RepoObject("notes/readme.txt", "x"));

        assertEquals("notes/readme.txt", change.path());
        assertNull(change.type());
        assertNull(change.name());

        RepoFileChange unknownType = RepoFileChange.from(new RepoObject("base/UNKNOWN/dbo/x.sql", "x"));
        assertNull(unknownType.type());
        assertEquals("x", unknownType.name());
    }

    @Test
    void shouldMapDbObjectsToObjectChanges() {
        assertEquals(new DbObjectChange(DbObjectType.TABLE, "dbo", "orders", DbObjectChange.Action.CREATE_OR_UPDATE),
                DbObjectChange.from(new DbObject("dbo", "orders", DbObjectType.TABLE, "CREATE TABLE dbo.orders (id int)")));
        assertEquals(DbObjectChange.Action.DROP,
                DbObjectChange.from(new DbObject("dbo", "orders", DbObjectType.TABLE, null)).action());
    }

    @Test
    void shouldDefaultMissingChangesToEmptyList() {
        assertEquals(java.util.List.of(), new SyncResult("db1", SyncResult.Status.FAILED, "boom").changes());
        assertEquals(java.util.List.of(), new SyncResult("db1", SyncResult.Status.FAILED, "boom", null).changes());
    }
}
