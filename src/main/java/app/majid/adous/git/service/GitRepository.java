package app.majid.adous.git.repository;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class GitRepository {

    private final Repository repo;

    public GitRepository(Repository repo) {
        this.repo = repo;
    }

    public Repository getRepo() {
        return repo;
    }

    public Optional<String> getFileContent(ObjectId id) {
        try {
            byte[] bytes = repo.open(id, Constants.OBJ_BLOB).getBytes();
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<String> getFileContentAtRef(String commitish, String path) {
        try {
            ObjectId id = repo.resolve(commitish);
            if (id == null) return Optional.empty();

            try (RevWalk rw = new RevWalk(repo)) {
                RevCommit commit = rw.parseCommit(id);
                try (TreeWalk tw = new TreeWalk(repo)) {
                    tw.addTree(commit.getTree());
                    tw.setRecursive(true);
                    tw.setFilter(PathFilter.create(path));

                    if (!tw.next()) return Optional.empty();

                    if (tw.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) {
                        return Optional.empty();
                    }

                    byte[] bytes = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB).getBytes();
                    return Optional.of(new String(bytes, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Map<String, String> getAllFilesAtRef(String commitish, String folder) throws IOException {
        ObjectId id = repo.resolve(commitish);
        if (id == null) return Map.of();

        String norm = folder.replace('\\', '/');
        while (norm.startsWith("./")) norm = norm.substring(2);
        if (norm.endsWith("/")) norm = norm.substring(0, norm.length() - 1);
        final String prefix = norm.isEmpty() ? "" : norm + "/";

        Map<String, String> result = new LinkedHashMap<>();

        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(id);
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                if (!norm.isEmpty()) {
                    tw.setFilter(PathFilter.create(norm));
                }

                while (tw.next()) {
                    if (!prefix.isEmpty() && !tw.getPathString().startsWith(prefix)) continue;
                    if (tw.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) continue;

                    byte[] bytes = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB).getBytes();
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    result.put(tw.getPathString(), content);
                }
            }
        }
        return result;
    }

    public boolean isEmptyRepo() throws IOException {
        return repo.resolve(Constants.HEAD) == null;
    }

    public boolean isHeadCommit(String commitish) throws IOException {
        return repo.resolve(commitish) != null &&
                repo.resolve(commitish).equals(repo.resolve(Constants.HEAD));
    }

    public boolean tagExists(String tagRef) throws IOException {
        return repo.findRef(tagRef) != null;
    }

    // Add tag to a specified commit
    public void addTagToCommit(String tagName, String commitish) throws IOException {
        ObjectId commitId = repo.resolve(commitish);
        if (commitId == null) {
            throw new IOException("Commit " + commitish + " not found.");
        }
        RefUpdate refUpdate = repo.updateRef(Constants.R_TAGS + tagName);
        refUpdate.setNewObjectId(commitId);
        refUpdate.setForceUpdate(true);
        RefUpdate.Result result = refUpdate.update();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("Failed to create tag " + tagName + ": " + result.name());
        }
    }
}
