package app.majid.adous.synchronizer.exception;

/**
 * Base exception for all synchronization-related errors.
 * This provides a common parent for all custom synchronization exceptions.
 */
public class SynchronizationException extends Exception {

    public SynchronizationException(String message) {
        super(message);
    }

    public SynchronizationException(String message, Throwable cause) {
        super(message, cause);
    }
}

