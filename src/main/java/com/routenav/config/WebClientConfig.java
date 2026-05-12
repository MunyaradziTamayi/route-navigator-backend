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

    @Value("${google.routes-base-url}")
    private String googleRoutesBaseUrl;

    @Value("${google.geocoding-base-url}")
    private String googleGeocodingBaseUrl;

    @Bean
    @Qualifier("googleRoutesClient")
    public WebClient googleRoutesWebClient() {
        return WebClient.builder()
                .baseUrl(googleRoutesBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    @Qualifier("googleGeocodingClient")
    public WebClient googleGeocodingWebClient() {
        return WebClient.builder()
                .baseUrl(googleGeocodingBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
