package com.routenav.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routenav.dto.ComputeRouteResponse;
import com.routenav.dto.JourneySummaryResponse;
import com.routenav.dto.MapboxDirectionsResponse;
import com.routenav.dto.RouteRequest;
import com.routenav.dto.RouteResponse;
import com.routenav.model.Route;
import com.routenav.repository.RouteRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class RoutingService {

    private final WebClient mapboxClient;

    private final String mapboxAccessToken;

    private final RouteRepository routeRepository;

    private final ObjectMapper objectMapper;

    public RoutingService(@Qualifier("mapboxClient") WebClient mapboxClient,
                          @Value("${mapbox.access-token}") String mapboxAccessToken,
                          RouteRepository routeRepository,
                          ObjectMapper objectMapper) {
        this.mapboxClient = mapboxClient;
        this.mapboxAccessToken = mapboxAccessToken;
        this.routeRepository = routeRepository;
        this.objectMapper = objectMapper;
    }

    public Mono<RouteResponse> calculateRoute(RouteRequest request) {
        return findCachedRoute(request, true)
                .map(route -> toRouteResponse(route))
                .switchIfEmpty(fetchAndSaveRoute(request).map(this::toRouteResponse));
    }

    public Mono<ComputeRouteResponse> calculateRouteRaw(RouteRequest request) {
        return findCachedRoute(request, true)
                .flatMap(this::toComputeRouteResponse)
                .switchIfEmpty(fetchAndSaveRoute(request).flatMap(this::toComputeRouteResponse));
    }

    public Mono<Double> calculateDistanceKm(double originLat, double originLon, double destinationLat, double destinationLon) {
        RouteRequest request = new RouteRequest();
        request.setOriginName("Origin");
        request.setDestinationName("Destination");
        request.setOriginLat(originLat);
        request.setOriginLon(originLon);
        request.setDestinationLat(destinationLat);
        request.setDestinationLon(destinationLon);

        return findCachedRoute(request, false)
                .map(Route::getDistanceKm)
                .switchIfEmpty(fetchAndSaveDistanceOnlyRoute(request).map(Route::getDistanceKm));
    }

    private Mono<Route> fetchAndSaveRoute(RouteRequest request) {
        String coordinates = request.getOriginLon() + "," + request.getOriginLat()
                + ";" + request.getDestinationLon() + "," + request.getDestinationLat();

        return mapboxClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/directions/v5/mapbox/driving/" + coordinates)
                        .queryParam("overview", "full")
                        .queryParam("geometries", "geojson")
                        .queryParam("steps", true)
                        .queryParam("alternatives", true)
                        .queryParam("access_token", requireMapboxAccessToken())
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "Mapbox Directions error (" + response.statusCode().value() + "): " + body))))
                .bodyToMono(MapboxDirectionsResponse.class)
                .flatMap(mapbox -> {
                    if (mapbox.getRoutes() == null || mapbox.getRoutes().isEmpty()) {
                        return Mono.error(new IllegalStateException(mapboxErrorMessage(mapbox)));
                    }

                    MapboxDirectionsResponse.MapboxRoute route = shortestRoute(mapbox);
                    if (route.getDistance() == null || route.getDuration() == null || route.getGeometry() == null) {
                        return Mono.error(new IllegalStateException("Mapbox Directions response missing distance/duration/geometry."));
                    }

                    double distanceMeters = route.getDistance();
                    int durationSecs = route.getDuration().intValue();
                    final String geometry = toGeometryJson(route.getGeometry());

                    return routeRepository.save(buildRouteEntity(request, distanceMeters / 1000.0, durationSecs, geometry));
                });
    }

    private Mono<Route> fetchAndSaveDistanceOnlyRoute(RouteRequest request) {
        String coordinates = request.getOriginLon() + "," + request.getOriginLat()
                + ";" + request.getDestinationLon() + "," + request.getDestinationLat();

        return mapboxClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/directions/v5/mapbox/driving/" + coordinates)
                        .queryParam("overview", "false")
                        .queryParam("alternatives", true)
                        .queryParam("access_token", requireMapboxAccessToken())
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "Mapbox Directions error (" + response.statusCode().value() + "): " + body))))
                .bodyToMono(MapboxDirectionsResponse.class)
                .flatMap(mapbox -> {
                    if (mapbox.getRoutes() == null || mapbox.getRoutes().isEmpty()) {
                        return Mono.error(new IllegalStateException(mapboxErrorMessage(mapbox)));
                    }

                    MapboxDirectionsResponse.MapboxRoute route = shortestRoute(mapbox);
                    if (route.getDistance() == null || route.getDuration() == null) {
                        return Mono.error(new IllegalStateException("Mapbox Directions response missing distance/duration."));
                    }

                    double distanceMeters = route.getDistance();
                    int durationSecs = route.getDuration().intValue();

                    return routeRepository.save(buildRouteEntity(request, distanceMeters / 1000.0, durationSecs, null));
                });
    }

    public Flux<RouteResponse> getSavedRoutes() {
        return routeRepository.findAllByOrderByCreatedAtDesc()
                .map(route -> RouteResponse.builder()
                        .id(route.getId())
                        .originName(route.getOriginName())
                        .destinationName(route.getDestinationName())
                        .distanceKm(route.getDistanceKm())
                        .straightLineDistanceKm(straightLineDistanceKm(route))
                        .duration(formatDuration(route.getDurationSeconds() == null ? 0 : route.getDurationSeconds()))
                        .geometry(route.getGeometry())
                        .instructions(List.of())
                        .build());
    }

    public Flux<JourneySummaryResponse> getJourneys(int limit) {
        return routeRepository.findAllByOrderByCreatedAtDesc()
                .take(Math.max(0, limit))
                .map(route -> JourneySummaryResponse.builder()
                        .id(route.getId() == null ? "" : String.valueOf(route.getId()))
                        .originLabel(route.getOriginName())
                        .destinationLabel(route.getDestinationName())
                        .distanceKm(route.getDistanceKm() == null ? 0.0 : route.getDistanceKm())
                        .straightLineDistanceKm(straightLineDistanceKm(route))
                        .createdAt(route.getCreatedAt() == null ? "" : route.getCreatedAt().toString())
                        .build());
    }

    public Mono<ComputeRouteResponse> getJourneyRoute(String id) {
        Long routeId;
        try {
            routeId = Long.valueOf(id);
        } catch (NumberFormatException e) {
            return Mono.just(ComputeRouteResponse.builder()
                    .geoJson(null)
                    .distanceKm(0.0)
                    .straightLineDistanceKm(0.0)
                    .durationSeconds(0)
                    .build());
        }

        return routeRepository.findById(routeId)
                .map(route -> {
                    Object geoJson = null;
                    try {
                        if (route.getGeometry() != null && !route.getGeometry().isBlank()) {
                            geoJson = objectMapper.readTree(route.getGeometry());
                        }
                    } catch (Exception ignored) {
                        geoJson = null;
                    }

                    return ComputeRouteResponse.builder()
                            .geoJson(geoJson)
                            .distanceKm(route.getDistanceKm() == null ? 0.0 : route.getDistanceKm())
                            .straightLineDistanceKm(straightLineDistanceKm(route))
                            .durationSeconds(route.getDurationSeconds() == null ? 0 : route.getDurationSeconds())
                            .build();
                })
                .defaultIfEmpty(ComputeRouteResponse.builder()
                        .geoJson(null)
                        .distanceKm(0.0)
                        .straightLineDistanceKm(0.0)
                        .durationSeconds(0)
                        .build());
    }

    private Mono<Route> findCachedRoute(RouteRequest request, boolean requireGeometry) {
        return routeRepository.findAllByOriginLatAndOriginLonAndDestinationLatAndDestinationLonOrderByCreatedAtDesc(
                        request.getOriginLat(),
                        request.getOriginLon(),
                        request.getDestinationLat(),
                        request.getDestinationLon())
                .filter(route -> route.getDistanceKm() != null)
                .filter(route -> !requireGeometry || (route.getGeometry() != null && !route.getGeometry().isBlank()))
                .next();
    }

    private Route buildRouteEntity(RouteRequest request, double distanceKm, int durationSeconds, String geometry) {
        return Route.builder()
                .originName(request.getOriginName())
                .destinationName(request.getDestinationName())
                .originLat(request.getOriginLat())
                .originLon(request.getOriginLon())
                .destinationLat(request.getDestinationLat())
                .destinationLon(request.getDestinationLon())
                .distanceKm(distanceKm)
                .durationSeconds(durationSeconds)
                .geometry(geometry)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private RouteResponse toRouteResponse(Route route) {
        return RouteResponse.builder()
                .id(route.getId())
                .originName(route.getOriginName())
                .destinationName(route.getDestinationName())
                .distanceKm(route.getDistanceKm())
                .straightLineDistanceKm(straightLineDistanceKm(route))
                .duration(formatDuration(route.getDurationSeconds() == null ? 0 : route.getDurationSeconds()))
                .geometry(route.getGeometry())
                .instructions(List.of())
                .build();
    }

    private Mono<ComputeRouteResponse> toComputeRouteResponse(Route route) {
        return Mono.fromCallable(() -> {
            Object geoJson = null;
            if (route.getGeometry() != null && !route.getGeometry().isBlank()) {
                geoJson = objectMapper.readTree(route.getGeometry());
            }

            return ComputeRouteResponse.builder()
                    .geoJson(geoJson)
                    .distanceKm(route.getDistanceKm())
                    .straightLineDistanceKm(straightLineDistanceKm(route))
                    .durationSeconds(route.getDurationSeconds())
                    .build();
        });
    }

    private double straightLineDistanceKm(Route route) {
        if (route.getOriginLat() == null || route.getOriginLon() == null
                || route.getDestinationLat() == null || route.getDestinationLon() == null) {
            return 0.0;
        }
        return haversineDistanceKm(route.getOriginLat(), route.getOriginLon(), route.getDestinationLat(), route.getDestinationLon());
    }

    private double haversineDistanceKm(double originLat, double originLon, double destinationLat, double destinationLon) {
        final double earthRadiusKm = 6371.0088;
        double latDistance = Math.toRadians(destinationLat - originLat);
        double lonDistance = Math.toRadians(destinationLon - originLon);
        double originLatRadians = Math.toRadians(originLat);
        double destinationLatRadians = Math.toRadians(destinationLat);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(originLatRadians) * Math.cos(destinationLatRadians)
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private String formatDuration(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        if (hours > 0) return hours + " hr " + minutes + " min";
        return minutes + " min";
    }

    private String toGeometryJson(Object geometry) {
        try {
            return objectMapper.writeValueAsString(geometry);
        } catch (Exception e) {
            return "";
        }
    }

    private MapboxDirectionsResponse.MapboxRoute shortestRoute(MapboxDirectionsResponse response) {
        return response.getRoutes().stream()
                .filter(route -> route.getDistance() != null)
                .min(Comparator.comparing(MapboxDirectionsResponse.MapboxRoute::getDistance))
                .orElse(response.getRoutes().get(0));
    }

    private String mapboxErrorMessage(MapboxDirectionsResponse response) {
        if (response.getMessage() != null && !response.getMessage().isBlank()) {
            return "Mapbox Directions error: " + response.getMessage();
        }
        if (response.getCode() != null && !response.getCode().isBlank()) {
            return "Mapbox Directions returned no routes (" + response.getCode() + ").";
        }
        return "No routes found from Mapbox Directions.";
    }

    private String requireMapboxAccessToken() {
        if (mapboxAccessToken == null || mapboxAccessToken.isBlank()) {
            throw new IllegalStateException("MAPBOX_ACCESS_TOKEN environment variable is required.");
        }
        return mapboxAccessToken;
    }
}
