package com.huntingmrxwellington;

import com.huntingmrxwellington.config.GameSettings;
import com.huntingmrxwellington.game.MapGraph;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class HuntingMrXApplication {

	public static void main(String[] args) {
		SpringApplication.run(HuntingMrXApplication.class, args);
	}

	/** The board, read once at startup from static/{game.map-file}. */
	@Bean
	MapGraph mapGraph(GameSettings settings) throws IOException {
		return MapGraph.parse(new ClassPathResource("static/" + settings.mapFile()).getContentAsByteArray());
	}
}
