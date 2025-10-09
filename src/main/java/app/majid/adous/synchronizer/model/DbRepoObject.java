package app.majid.adous.synchronizer.model;

public record DbRepoObject(
        RepoObject base,
        RepoObject diff
) {
}
