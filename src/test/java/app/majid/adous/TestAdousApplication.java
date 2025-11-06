package app.majid.adous;

import org.springframework.boot.SpringApplication;

public class TestAdousApplication {

	static void main(String[] args) {
		SpringApplication.from(AdousApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
