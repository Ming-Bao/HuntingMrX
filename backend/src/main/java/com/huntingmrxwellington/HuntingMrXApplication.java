package com.huntingmrxwellington;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HuntingMrXApplication {

	public static void main(String[] args) {
		SpringApplication.run(HuntingMrXApplication.class, args);
	}

}
