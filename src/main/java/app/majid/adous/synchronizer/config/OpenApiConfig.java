package app.majid.adous.synchronizer.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /** Name of the security scheme for the X-API-Key header. */
    public static final String API_KEY_SCHEME = "apiKey";

    /**
     * No servers are listed, so Swagger UI calls the host it was loaded from.
     * The API key scheme gives Swagger UI an "Authorize" button that sends X-API-Key.
     */
    @Bean
    public OpenAPI adousOpenAPI(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI()
                .info(new Info()
                        .title("Adous - Database Synchronizer API")
                        .description("REST API for synchronizing SQL Server databases with Git repository. " +
                                "Provides bidirectional sync between database schemas and version-controlled SQL scripts.")
                        .version(build != null ? build.getVersion() : "dev")
                        .contact(new Contact()
                                .name("Adous on GitHub")
                                .url("https://github.com/majidkabir/Adous"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("The API key configured with API_KEY")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
