package app.majid.adous.db.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseContextHolderTest {

    @AfterEach
    void tearDown() {
        DatabaseContextHolder.clear();
    }

    @Test
    void shouldResolveDatabaseIgnoringCaseWhenConfiguredWithUppercase() {
        DatabaseContextHolder.setAvailableDbs(Set.of("MyDb"));

        DatabaseContextHolder.setCurrentDb("mydb");
        assertEquals("mydb", DatabaseContextHolder.getCurrentDb());

        DatabaseContextHolder.setCurrentDb("MYDB");
        assertEquals("mydb", DatabaseContextHolder.getCurrentDb());
    }

    @Test
    void shouldRejectUnknownDatabase() {
        DatabaseContextHolder.setAvailableDbs(Set.of("db1"));

        assertThrows(IllegalArgumentException.class, () -> DatabaseContextHolder.setCurrentDb("db2"));
        assertNull(DatabaseContextHolder.getCurrentDb());
    }
}
