package app.majid.adous.git.service;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class GitDiffService {

    private final Repository repo;

    public GitDiffService(Repository repo) {
        this.repo = repo;
    }

    public List<DiffEntry> getDiff(String commitId, String ancestorId, List<String> folders) throws IOException {
        try (RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            df.setRepository(repo);
            df.setDetectRenames(true);

            // Add path filters for the specified folders
            if (folders != null && !folders.isEmpty()) {
                df.setPathFilter(PathFilterGroup.createFromStrings(folders));
            }

            RevCommit commit = walk.parseCommit(repo.resolve(commitId));

            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            CanonicalTreeParser newTree = new CanonicalTreeParser();

            if (ancestorId == null || repo.resolve(ancestorId) == null) {
                try (ObjectInserter inserter = repo.getObjectDatabase().newInserter()) {
                    ObjectId emptyTreeId = inserter.insert(Constants.OBJ_TREE, new byte[0]);
                    oldTree.reset(reader, emptyTreeId);
                }
            } else {
                RevCommit ancestor = walk.parseCommit(repo.resolve(ancestorId));
                oldTree.reset(reader, ancestor.getTree());
            }

            newTree.reset(reader, commit.getTree());

            return df.scan(oldTree, newTree);
        }
    }
}
