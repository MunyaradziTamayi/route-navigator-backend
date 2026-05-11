package com.routenav.service;

import com.routenav.dto.GeocodeResponse;
import com.routenav.dto.MapboxGeocodingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class GeocodingService {

    private final WebClient mapboxClient;

    private final String mapboxAccessToken;

    public GeocodingService(@Qualifier("mapboxClient") WebClient mapboxClient,
                            @Value("${mapbox.access-token}") String mapboxAccessToken) {
        this.mapboxClient = mapboxClient;
        this.mapboxAccessToken = mapboxAccessToken;
    }

    public Flux<GeocodeResponse> search(String query) {
        return mapboxClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/geocode/v6/forward")
                        .queryParam("q", query)
                        .queryParam("limit", 5)
                        .queryParam("autocomplete", false)
                        .queryParam("access_token", requireMapboxAccessToken())
                        .build())
                .retrieve()
                .bodyToMono(MapboxGeocodingResponse.class)
                .flatMapMany(response -> Flux.fromIterable(response.getFeatures() == null ? java.util.List.of() : response.getFeatures()))
                .filter(feature -> feature.getGeometry() != null
                        && feature.getGeometry().getCoordinates() != null
                        && feature.getGeometry().getCoordinates().size() >= 2)
                .map(feature -> {
                    var coordinates = feature.getGeometry().getCoordinates();
                    return GeocodeResponse.builder()
                            .displayName(displayName(feature))
                            .lat(coordinates.get(1))
                            .lon(coordinates.get(0))
                            .build();
                });
    }

    public Mono<GeocodeResponse> geocodeFirst(String query) {
        return search(query)
                .filter(result -> result.getLat() != null && result.getLon() != null)
                .next()
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Could not geocode address: " + query)));
    }

    private String displayName(MapboxGeocodingResponse.MapboxFeature feature) {
        if (feature.getProperties() == null) {
            return "";
        }

        String fullAddress = feature.getProperties().getFullAddress();
        if (StringUtils.hasText(fullAddress)) {
            return fullAddress;
        }

        String name = feature.getProperties().getName();
        String place = feature.getProperties().getPlaceFormatted();
        if (StringUtils.hasText(name) && StringUtils.hasText(place)) {
            return name + ", " + place;
        }
        if (StringUtils.hasText(name)) {
            return name;
        }
        return StringUtils.hasText(place) ? place : "";
    }

    private String requireMapboxAccessToken() {
        if (!StringUtils.hasText(mapboxAccessToken)) {
            throw new IllegalStateException("MAPBOX_ACCESS_TOKEN environment variable is required.");
        }
        return mapboxAccessToken;
    }
}
