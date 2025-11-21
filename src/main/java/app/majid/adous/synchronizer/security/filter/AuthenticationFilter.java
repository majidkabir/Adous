package app.majid.adous.synchronizer.security.filter;

import jakarta.servlet.Filter;

/**
 * Marker interface for authentication filters.
 * Allows different authentication implementations to be used interchangeably.
 */
public interface AuthenticationFilter extends Filter {
}

