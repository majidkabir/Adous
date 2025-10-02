package app.majid.Adous;

import org.springframework.boot.SpringApplication;

public class TestAdousApplication {

	public static void main(String[] args) {
		SpringApplication.from(AdousApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
