package app.majid.adous.synchronizer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiConfigTest {

    @Test
    void shouldDeclareApiKeyHeaderScheme() {
        SecurityScheme scheme = openApi(null).getComponents().getSecuritySchemes().get(OpenApiConfig.API_KEY_SCHEME);

        assertEquals(SecurityScheme.Type.APIKEY, scheme.getType());
        assertEquals(SecurityScheme.In.HEADER, scheme.getIn());
        assertEquals("X-API-Key", scheme.getName());
    }

    @Test
    void shouldRequireApiKeyForAllOperations() {
        assertEquals(List.of(OpenApiConfig.API_KEY_SCHEME),
                openApi(null).getSecurity().stream().flatMap(r -> r.keySet().stream()).toList());
    }

    @Test
    void shouldNotHardcodeServers() {
        assertNull(openApi(null).getServers(), "Swagger UI should call the host it was loaded from");
    }

    @Test
    void shouldTakeVersionFromBuildInfo() {
        Properties properties = new Properties();
        properties.setProperty("version", "1.2.3");

        assertEquals("1.2.3", openApi(new BuildProperties(properties)).getInfo().getVersion());
        assertEquals("dev", openApi(null).getInfo().getVersion());
    }

    private static OpenAPI openApi(BuildProperties buildProperties) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (buildProperties != null) {
            beanFactory.addBean("buildProperties", buildProperties);
        }
        ObjectProvider<BuildProperties> provider = beanFactory.getBeanProvider(BuildProperties.class);
        return new OpenApiConfig().adousOpenAPI(provider);
    }
}
