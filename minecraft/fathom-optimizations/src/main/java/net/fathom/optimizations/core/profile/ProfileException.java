package net.fathom.optimizations.core.profile;

/**
 * A profile could not be read, written or used.
 *
 * <p>Checked, because every caller of the profile store is somewhere a failure
 * has a sensible answer — keep the current profile, show the reason, skip the
 * corrupt document — and an unchecked exception is how that answer gets
 * forgotten in exactly one call site.
 */
public final class ProfileException extends Exception {

    private static final long serialVersionUID = 1L;

    public ProfileException(String message) {
        super(message);
    }

    public ProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
