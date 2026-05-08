package com.routenav.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${osrm.base-url}")
    private String osrmBaseUrl;

    @Value("${nominatim.base-url}")
    private String nominatimBaseUrl;

    @Bean
    @Qualifier("osrmClient")
    public WebClient osrmWebClient() {
        return WebClient.builder()
                .baseUrl(osrmBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    @Qualifier("nominatimClient")
    public WebClient nominatimWebClient() {
        return WebClient.builder()
                .baseUrl(nominatimBaseUrl)
                .defaultHeader("User-Agent", "RouteNav/1.0")
                .build();
    }
}

