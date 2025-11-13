package app.majid.adous.synchronizer.model;

public record SyncResult(String dbName, Status status, String message) {
    public enum Status { SYNCED, SUCCESS_DRY_RUN, SKIPPED_NOT_ONBOARDED, SKIPPED_OUT_OF_SYNC, FAILED }
}
