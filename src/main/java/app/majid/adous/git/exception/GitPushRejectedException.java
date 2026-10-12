package app.majid.adous.git.exception;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.util.List;

/**
 * Thrown when the remote rejects one or more ref updates during a push.
 * <p>
 * JGit's {@code PushCommand} does not throw on rejected refs (e.g. non-fast-forward),
 * it only reports them in the {@code PushResult}. This exception makes such rejections
 * visible to callers instead of silently reporting success.
 */
public class GitPushRejectedException extends GitAPIException {

    private final List<String> rejectedRefs;

    public GitPushRejectedException(List<String> rejectedRefs) {
        super("Push rejected by remote: " + String.join(", ", rejectedRefs));
        this.rejectedRefs = List.copyOf(rejectedRefs);
    }

    public List<String> getRejectedRefs() {
        return rejectedRefs;
    }
}
