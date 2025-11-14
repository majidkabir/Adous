package app.majid.adous.synchronizer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI adousOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Adous - Database Synchronizer API")
                        .description("REST API for synchronizing SQL Server databases with Git repository. " +
                                "Provides bidirectional sync between database schemas and version-controlled SQL scripts.")
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact()
                                .name("Majid Ghafouri")
                                .email("majid.ghafouri@example.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Development server"),
                        new Server()
                                .url("https://api.production.com")
                                .description("Production server")
                ));
    }
}

