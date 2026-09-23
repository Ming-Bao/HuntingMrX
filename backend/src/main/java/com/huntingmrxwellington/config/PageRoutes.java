package com.huntingmrxwellington.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Sends the frontend's page addresses to index.html, so a refresh or a shared link on e.g. /lobby/ABC123
 *  loads the app and its router picks the page. The Docker build puts the built frontend in static/. */
@Configuration
public class PageRoutes implements WebMvcConfigurer {

    /** The routes in frontend/src/app/router.ts, which this list must match. "/" is Spring's welcome page. */
    private static final List<String> PAGES = List.of(
            "/create", "/join", "/lobby/*", "/game/*", "/game/*/end", "/{code:[A-Za-z0-9]{6}}");

    /**
     * Forwards each page address to index.html.
     *
     * @param registry Spring's view-controller settings
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String page : PAGES) registry.addViewController(page).setViewName("forward:/index.html");
    }
}
