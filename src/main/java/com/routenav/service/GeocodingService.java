package com.routenav.service;

import com.routenav.dto.GeocodeResponse;
import com.routenav.dto.GoogleGeocodingResponse;
import com.routenav.model.GeocodedAddress;
import com.routenav.repository.GeocodedAddressRepository;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class GeocodingService {

    private final WebClient googleGeocodingClient;

    private final String googleApiKey;

    private final GeocodedAddressRepository geocodedAddressRepository;

    public GeocodingService(@Qualifier("googleGeocodingClient") WebClient googleGeocodingClient,
                            @Value("${google.api-key}") String googleApiKey,
                            GeocodedAddressRepository geocodedAddressRepository) {
        this.googleGeocodingClient = googleGeocodingClient;
        this.googleApiKey = googleApiKey;
        this.geocodedAddressRepository = geocodedAddressRepository;
    }

    public Flux<GeocodeResponse> search(String query) {
        return googleGeocodingClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/maps/api/geocode/json")
                        .queryParam("address", query)
                        .queryParam("key", requireGoogleApiKey())
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "Google Geocoding error (" + response.statusCode().value() + "): " + body))))
                .bodyToMono(GoogleGeocodingResponse.class)
                .flatMapMany(response -> {
                    if (!"OK".equals(response.getStatus()) && !"ZERO_RESULTS".equals(response.getStatus())) {
                        return Flux.error(new IllegalStateException(googleGeocodingErrorMessage(response)));
                    }
                    return Flux.fromIterable(response.getResults() == null ? java.util.List.of() : response.getResults());
                })
                .filter(result -> result.getGeometry() != null
                        && result.getGeometry().getLocation() != null
                        && result.getGeometry().getLocation().getLat() != null
                        && result.getGeometry().getLocation().getLng() != null)
                .take(5)
                .map(result -> {
                    var location = result.getGeometry().getLocation();
                    return GeocodeResponse.builder()
                            .displayName(displayName(result))
                            .lat(location.getLat())
                            .lon(location.getLng())
                            .build();
                });
    }

    public Mono<GeocodeResponse> geocodeFirst(String query) {
        String normalizedAddress = normalizeAddress(query);
        return findCachedAddress(normalizedAddress)
                .map(this::toGeocodeResponse)
                .switchIfEmpty(fetchAndCacheFirst(query, normalizedAddress));
    }

    private Mono<GeocodedAddress> findCachedAddress(String normalizedAddress) {
        return geocodedAddressRepository.findByNormalizedAddress(normalizedAddress)
                .onErrorResume(error -> Mono.empty());
    }

    private Mono<GeocodeResponse> fetchAndCacheFirst(String query, String normalizedAddress) {
        return search(query)
                .filter(result -> result.getLat() != null && result.getLon() != null)
                .next()
                .flatMap(result -> geocodedAddressRepository.save(GeocodedAddress.builder()
                                .normalizedAddress(normalizedAddress)
                                .displayName(result.getDisplayName())
                                .lat(result.getLat())
                                .lon(result.getLon())
                                .createdAt(LocalDateTime.now())
                                .build())
                        .map(this::toGeocodeResponse)
                        .onErrorResume(error -> findCachedAddress(normalizedAddress)
                                .map(this::toGeocodeResponse)
                                .switchIfEmpty(Mono.just(result))))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Could not geocode address: " + query)));
    }

    private GeocodeResponse toGeocodeResponse(GeocodedAddress address) {
        return GeocodeResponse.builder()
                .displayName(address.getDisplayName())
                .lat(address.getLat())
                .lon(address.getLon())
                .build();
    }

    private String normalizeAddress(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ENGLISH);
    }

    private String displayName(GoogleGeocodingResponse.GoogleGeocodingResult result) {
        return StringUtils.hasText(result.getFormattedAddress()) ? result.getFormattedAddress() : "";
    }

    private String googleGeocodingErrorMessage(GoogleGeocodingResponse response) {
        if (StringUtils.hasText(response.getErrorMessage())) {
            return "Google Geocoding error: " + response.getErrorMessage();
        }
        if (StringUtils.hasText(response.getStatus())) {
            return "Google Geocoding returned status: " + response.getStatus();
        }
        return "Google Geocoding failed.";
    }

    private String requireGoogleApiKey() {
        if (!StringUtils.hasText(googleApiKey)) {
            throw new IllegalStateException("GOOGLE_API_KEY environment variable is required.");
        }
        return googleApiKey;
    }
}
