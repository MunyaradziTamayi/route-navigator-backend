package com.routenav.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI routeNavigatorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Route Navigator API")
                        .version("1.0")
                        .description("API documentation for routes, journeys, geocoding, visits, workers, and mileage uploads."));
    }
}
