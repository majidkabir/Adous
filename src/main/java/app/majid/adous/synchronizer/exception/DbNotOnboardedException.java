package app.majid.adous.synchronizer.exception;

public class DbNotOnboardedException extends Exception {
    public  DbNotOnboardedException(String message) {
        super("The DB '" + message + "' is not onboarded in the repository");
    }
}
