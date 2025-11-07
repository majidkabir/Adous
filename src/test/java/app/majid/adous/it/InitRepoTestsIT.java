package app.majid.adous.it;

import app.majid.adous.git.service.GitService;
import app.majid.adous.synchronizer.service.DatabaseRepositorySynchronizerService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InitRepoTestsIT {

    @Autowired
    DatabaseRepositorySynchronizerService synchronizerService;

    @Autowired
    GitService gitService;

    @Test
    void testSynchronizerServiceNotNull() throws GitAPIException, IOException {
        synchronizerService.initRepo("db1");

        // All objects should exist in the git repo initially
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/PROCEDURE/dbo/proc1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/PROCEDURE/dbo/proc2.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/PROCEDURE/dbo/prefix2_proc1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/PROCEDURE/dbo/prefix3_proc1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/PROCEDURE/dbo/prefix4_proc1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/TRIGGER/dbo/trigger1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/TRIGGER/dbo/trigger2.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/TRIGGER/dbo/prefix1_trigger1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/TRIGGER/dbo/prefix3_trigger1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/TRIGGER/dbo/prefix4_trigger1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/VIEW/dbo/view1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/VIEW/dbo/view2.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/VIEW/dbo/prefix1_view1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/VIEW/dbo/prefix2_view1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/VIEW/dbo/prefix4_view1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/FUNCTION/dbo/func1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/FUNCTION/dbo/func2.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/FUNCTION/dbo/prefix1_func1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/FUNCTION/dbo/prefix2_func1.sql").isPresent());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/FUNCTION/dbo/prefix3_func1.sql").isPresent());
        // All ignored objects should not exist in the git repo initially
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/PROCEDURE/dbo/prefix1_proc1.sql").isEmpty());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/VIEW/dbo/prefix3_view1.sql").isEmpty());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/TRIGGER/dbo/prefix2_trigger1.sql").isEmpty());
        assertTrue(gitService.getFileContentAtRef("HEAD", "base/FUNCTION/dbo/prefix4_func1.sql").isEmpty());
        // Definition of objects should be correct
        String expectedProc1Definition = normalizeLineEndings("""
                SET ANSI_NULLS ON;
                GO
                SET QUOTED_IDENTIFIER ON;
                GO
                CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 1 executed' AS Message; END
                GO""");
        String actualProc1Definition = normalizeLineEndings(
                gitService.getFileContentAtRef("HEAD", "base/PROCEDURE/dbo/proc1.sql").get());
        assertEquals(normalizeLineEndings(expectedProc1Definition), normalizeLineEndings(actualProc1Definition));
        String expectedTrigger1Definition = """
                SET ANSI_NULLS ON;
                GO
                SET QUOTED_IDENTIFIER ON;
                GO
                CREATE TRIGGER trigger1 ON table1 AFTER INSERT AS BEGIN INSERT INTO table1 (name) VALUES ('test name 1'); END
                GO""";
        String actualTrigger1Definition = gitService.getFileContentAtRef("HEAD", "base/TRIGGER/dbo/trigger1.sql").get();
        assertEquals(normalizeLineEndings(expectedTrigger1Definition), normalizeLineEndings(actualTrigger1Definition));
        String expectedView1Definition = """
                SET ANSI_NULLS ON;
                GO
                SET QUOTED_IDENTIFIER ON;
                GO
                CREATE VIEW view1 AS SELECT id FROM table1
                GO""";
        String actualView1Definition = gitService.getFileContentAtRef("HEAD", "base/VIEW/dbo/view1.sql").get();
        assertEquals(normalizeLineEndings(expectedView1Definition), normalizeLineEndings(actualView1Definition));
        String expectedFunction1Definition = """
                SET ANSI_NULLS ON;
                GO
                SET QUOTED_IDENTIFIER ON;
                GO
                CREATE FUNCTION func1() RETURNS INT BEGIN RETURN 1 END
                GO""";
        String actualFunction1Definition = gitService.getFileContentAtRef("HEAD", "base/FUNCTION/dbo/func1.sql").get();
        assertEquals(normalizeLineEndings(expectedFunction1Definition), normalizeLineEndings(actualFunction1Definition));
    }

    private String normalizeLineEndings(String input) {
        return input.replace("\r\n", "\n").replace("\r", "\n");
    }
}
