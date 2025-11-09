package app.majid.adous.git.service;

import app.majid.adous.git.config.GitProperties;
import app.majid.adous.git.mapper.DbObjectMapper;
import app.majid.adous.git.repository.GitRepository;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.RepoObject;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.eclipse.jgit.diff.DiffEntry.ChangeType.*;

@Service
public class GitService {

    private static final Set<DiffEntry.ChangeType> ADDITION_OR_MODIFICATION = Set.of(ADD, MODIFY, RENAME, COPY);
    private static final Set<DiffEntry.ChangeType> RENAME_OR_DELETION = Set.of(RENAME, COPY, DELETE);

    private final GitRepository gitRepository;
    private final GitDiffService diffService;
    private final GitCommitService commitService;
    private final GitRemoteService remoteService;
    private final DbObjectMapper dbObjectMapper;
    private final String baseRootPath;
    private final String diffRootPath;
    private final String defaultBranchRef;

    public GitService(GitRepository gitRepository,
                      GitDiffService diffService,
                      GitCommitService commitService,
                      GitRemoteService remoteService,
                      DbObjectMapper dbObjectMapper,
                      GitProperties gitProperties) {
        this.gitRepository = gitRepository;
        this.diffService = diffService;
        this.commitService = commitService;
        this.remoteService = remoteService;
        this.dbObjectMapper = dbObjectMapper;
        this.baseRootPath = gitProperties.baseRootPath();
        this.diffRootPath = gitProperties.diffRootPath();
        this.defaultBranchRef = Constants.R_HEADS + gitProperties.defaultBranch();
    }

    public Optional<String> getFileContent(ObjectId id) {
        return gitRepository.getFileContent(id);
    }

    public Optional<String> getFileContentAtRef(String commitish, String path) {
        return gitRepository.getFileContentAtRef(commitish, path);
    }

    public Map<String, String> getAllFilesAtRef(String commitish, String folder) throws IOException {
        return gitRepository.getAllFilesAtRef(commitish, folder);
    }

    public List<DiffEntry> getDiff(String commitId, String ancestorId, List<String> folders) throws IOException {
        return diffService.getDiff(commitId, ancestorId, folders);
    }

    public List<DbObject> getRepoChangesToApplyToDb(String commitish, String dbName) throws IOException {
        List<DbObject> dbObjects = new ArrayList<>();

        List<DiffEntry> diff = getDiff(commitish, getTag(dbName), List.of(baseRootPath, diffRootPath + "/" + dbName));
        diff.forEach(e -> {
            if (newOrModifiedInBaseAndNotExistInDiff(e, commitish)) {
                dbObjects.add(dbObjectMapper.fromPath(e.getNewPath(), getFileContent(e.getNewId().toObjectId()).orElse(null)));
            }
            if (removedFromBaseAndNotExistInDiff(e, commitish)) {
                dbObjects.add(dbObjectMapper.fromPath(e.getOldPath(), null));
            }
            if (newOrModifiedInDiff(e)) {
                dbObjects.add(dbObjectMapper.fromPath(e.getNewPath(), getFileContent(e.getNewId().toObjectId()).orElse(null)));
            }
            if (removedFromDiff(e)) {
                Optional<String> c = getFileContentAtRef(commitish, e.getOldPath().replace(diffRootPath, baseRootPath));
                dbObjects.add(dbObjectMapper.fromPath(e.getOldPath(), c.orElse(null)));
            }
        });

        return dbObjects;
    }

    public ObjectId applyChangesAndPush(List<RepoObject> changes, String commitMessage, List<String> tags)
            throws IOException, GitAPIException {
        ObjectId commitId = commitService.applyChanges(changes, commitMessage, defaultBranchRef);
        for (String tag : tags) {
            gitRepository.addTagToCommit(tag, defaultBranchRef);
        }
        remoteService.push(defaultBranchRef);
        return commitId;
    }

    public void addTags(List<String> tags, String commitish) throws IOException, GitAPIException {
        for (String tag : tags) {
            gitRepository.addTagToCommit(tag, commitish);
        }
        remoteService.push(commitish);
    }

    public boolean isEmptyRepo() throws IOException {
        return gitRepository.isEmptyRepo();
    }

    public boolean isHeadCommit(String commitish) throws IOException {
        return gitRepository.isHeadCommit(commitish);
    }

    public boolean tagExists(String tagName) throws IOException {
        return gitRepository.tagExists(getTag(tagName));
    }

    public String getTag(String tagName) {
        return Constants.R_TAGS + tagName;
    }

    public String getDiffPath() {
        return diffRootPath;
    }

    public String getBasePath() {
        return baseRootPath;
    }

    private boolean removedFromDiff(DiffEntry e) {
        return RENAME_OR_DELETION.contains(e.getChangeType()) && e.getOldPath().startsWith(diffRootPath);
    }

    private boolean newOrModifiedInDiff(DiffEntry e) {
        return ADDITION_OR_MODIFICATION.contains(e.getChangeType()) && e.getNewPath().startsWith(diffRootPath);
    }

    private boolean removedFromBaseAndNotExistInDiff(DiffEntry e, String commitish) {
        if (RENAME_OR_DELETION.contains(e.getChangeType()) && e.getOldPath().startsWith(baseRootPath)) {
            Optional<String> c = getFileContentAtRef(commitish, e.getNewPath().replace(baseRootPath, diffRootPath));
            return c.isEmpty();
        }
        return false;
    }

    private boolean newOrModifiedInBaseAndNotExistInDiff(DiffEntry e, String commitish) {
        if (ADDITION_OR_MODIFICATION.contains(e.getChangeType()) && e.getNewPath().startsWith(baseRootPath)) {
            Optional<String> c = getFileContentAtRef(commitish, e.getNewPath().replace(baseRootPath, diffRootPath));
            return c.isEmpty();
        }
        return false;
    }
}
