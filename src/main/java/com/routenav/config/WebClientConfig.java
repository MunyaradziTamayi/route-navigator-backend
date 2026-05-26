package com.routenav.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

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
                .clientConnector(new ReactorClientHttpConnector(googleHttpClient()))
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

    private HttpClient googleHttpClient() {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .responseTimeout(Duration.ofSeconds(60));
    }
}
