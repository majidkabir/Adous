package app.majid.adous;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
public class AdousApplication {

	public static void main(String[] args) {
		SpringApplication.run(AdousApplication.class, args);
	}

}
