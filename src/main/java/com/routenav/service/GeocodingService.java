package com.routenav.service;

import com.routenav.dto.GeocodeResponse;
import com.routenav.dto.NominatimResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Service
public class GeocodingService {

    private final WebClient nominatimClient;

    public GeocodingService(@Qualifier("nominatimClient") WebClient nominatimClient) {
        this.nominatimClient = nominatimClient;
    }

    public Flux<GeocodeResponse> search(String query) {
        return nominatimClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search")
                        .queryParam("q", query)
                        .queryParam("format", "json")
                        .queryParam("limit", 5)
                        .build())
                .retrieve()
                .bodyToFlux(NominatimResponse.class)
                .map(row -> GeocodeResponse.builder()
                        .displayName(row.getDisplayName())
                        .lat(safeParseDouble(row.getLat()))
                        .lon(safeParseDouble(row.getLon()))
                        .build());
    }

    private Double safeParseDouble(String value) {
        if (value == null) return null;
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
