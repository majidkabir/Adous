package app.majid.adous.db.config;

public record DbConfig(
        String url,
        String username,
        String password,
        String driverClassName
) {
}
