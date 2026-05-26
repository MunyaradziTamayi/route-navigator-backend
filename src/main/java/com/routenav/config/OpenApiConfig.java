package com.routenav.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI routeNavigatorOpenApi(@Value("${app.public-url:}") String publicUrl) {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("Route Navigator API")
                        .version("1.0")
                        .description("API documentation for routes, journeys, geocoding, visits, workers, and mileage uploads."));

        if (publicUrl != null && !publicUrl.isBlank()) {
            openAPI.setServers(List.of(new Server().url(publicUrl)));
        }

        return openAPI;
    }
}
