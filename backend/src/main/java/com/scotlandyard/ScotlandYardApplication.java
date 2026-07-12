package com.scotlandyard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ScotlandYardApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScotlandYardApplication.class, args);
	}

}
