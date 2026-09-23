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

/** Starts the Spring Boot server. Scheduling runs the idle-game check, and the
 *  configuration-properties scan picks up GameSettings. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class HuntingMrXApplication {

    /**
     * Starts the server.
     *
     * @param args command-line arguments, passed on to Spring
     */
    public static void main(String[] args) {
        SpringApplication.run(HuntingMrXApplication.class, args);
    }

    /**
     * The board, read once at startup. A missing or invalid map file stops the server from starting.
     *
     * @param settings where game.map-file comes from
     * @return the parsed board
     * @throws IOException if the map file can't be read
     */
    @Bean
    MapGraph mapGraph(GameSettings settings) throws IOException {
        return MapGraph.parse(new ClassPathResource("static/" + settings.mapFile()).getContentAsByteArray());
    }
}
