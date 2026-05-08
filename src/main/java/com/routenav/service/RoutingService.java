package com.routenav.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routenav.dto.ComputeRouteResponse;
import com.routenav.dto.JourneySummaryResponse;
import com.routenav.dto.OsrmResponse;
import com.routenav.dto.RouteRequest;
import com.routenav.dto.RouteResponse;
import com.routenav.model.Route;
import com.routenav.repository.RouteRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class RoutingService {

    private final WebClient osrmClient;

    private final RouteRepository routeRepository;

    private final ObjectMapper objectMapper;

    public RoutingService(@Qualifier("osrmClient") WebClient osrmClient, RouteRepository routeRepository, ObjectMapper objectMapper) {
        this.osrmClient = osrmClient;
        this.routeRepository = routeRepository;
        this.objectMapper = objectMapper;
    }

    public Mono<RouteResponse> calculateRoute(RouteRequest request) {
        String coordinates = request.getOriginLon() + "," + request.getOriginLat()
                + ";" + request.getDestinationLon() + "," + request.getDestinationLat();

        return osrmClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/route/v1/driving/" + coordinates)
                        .queryParam("overview", "full")
                        .queryParam("geometries", "geojson")
                        .queryParam("steps", true)
                        .build())
                .retrieve()
                .bodyToMono(OsrmResponse.class)
                .flatMap(osrm -> {
                    if (osrm.getRoutes() == null || osrm.getRoutes().isEmpty()) {
                        return Mono.error(new IllegalStateException("No routes found from OSRM."));
                    }

                    OsrmResponse.OsrmRoute osrmRoute = osrm.getRoutes().get(0);
                    if (osrmRoute.getDistance() == null || osrmRoute.getDuration() == null || osrmRoute.getGeometry() == null) {
                        return Mono.error(new IllegalStateException("OSRM response missing distance/duration/geometry."));
                    }

                    double distanceMeters = osrmRoute.getDistance();
                    int durationSecs = osrmRoute.getDuration().intValue();
                    final String geometry = toGeometryJson(osrmRoute.getGeometry());

                    List<String> instructions = List.of();

                    Route entity = Route.builder()
                            .originName(request.getOriginName())
                            .destinationName(request.getDestinationName())
                            .originLat(request.getOriginLat())
                            .originLon(request.getOriginLon())
                            .destinationLat(request.getDestinationLat())
                            .destinationLon(request.getDestinationLon())
                            .distanceKm(distanceMeters / 1000.0)
                            .durationSeconds(durationSecs)
                            .geometry(geometry)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return routeRepository.save(entity)
                            .map(saved -> RouteResponse.builder()
                                    .id(saved.getId())
                                    .originName(request.getOriginName())
                                    .destinationName(request.getDestinationName())
                                    .distanceKm(saved.getDistanceKm())
                                    .duration(formatDuration(durationSecs))
                                    .geometry(geometry)
                                    .instructions(instructions)
                                    .build());
                });
    }

    public Mono<ComputeRouteResponse> calculateRouteRaw(RouteRequest request) {
        String coordinates = request.getOriginLon() + "," + request.getOriginLat()
                + ";" + request.getDestinationLon() + "," + request.getDestinationLat();

        return osrmClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/route/v1/driving/" + coordinates)
                        .queryParam("overview", "full")
                        .queryParam("geometries", "geojson")
                        .queryParam("steps", true)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "OSRM error (" + response.statusCode().value() + "): " + body))))
                .bodyToMono(OsrmResponse.class)
                .flatMap(osrm -> {
                    if (osrm.getRoutes() == null || osrm.getRoutes().isEmpty()) {
                        return Mono.error(new IllegalStateException("No routes found from OSRM."));
                    }

                    OsrmResponse.OsrmRoute osrmRoute = osrm.getRoutes().get(0);
                    if (osrmRoute.getDistance() == null || osrmRoute.getDuration() == null || osrmRoute.getGeometry() == null) {
                        return Mono.error(new IllegalStateException("OSRM response missing distance/duration/geometry."));
                    }

                    double distanceMeters = osrmRoute.getDistance();
                    int durationSecs = osrmRoute.getDuration().intValue();
                    String geometryJson = toGeometryJson(osrmRoute.getGeometry());

                    Route entity = Route.builder()
                            .originName(request.getOriginName())
                            .destinationName(request.getDestinationName())
                            .originLat(request.getOriginLat())
                            .originLon(request.getOriginLon())
                            .destinationLat(request.getDestinationLat())
                            .destinationLon(request.getDestinationLon())
                            .distanceKm(distanceMeters / 1000.0)
                            .durationSeconds(durationSecs)
                            .geometry(geometryJson)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return routeRepository.save(entity)
                            .map(saved -> ComputeRouteResponse.builder()
                                    .geoJson(osrmRoute.getGeometry())
                                    .distanceKm(saved.getDistanceKm())
                                    .durationSeconds(durationSecs)
                                    .build());
                });
    }

    public Flux<RouteResponse> getSavedRoutes() {
        return routeRepository.findAllByOrderByCreatedAtDesc()
                .map(route -> RouteResponse.builder()
                        .id(route.getId())
                        .originName(route.getOriginName())
                        .destinationName(route.getDestinationName())
                        .distanceKm(route.getDistanceKm())
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
                            .durationSeconds(route.getDurationSeconds() == null ? 0 : route.getDurationSeconds())
                            .build();
                })
                .defaultIfEmpty(ComputeRouteResponse.builder()
                        .geoJson(null)
                        .distanceKm(0.0)
                        .durationSeconds(0)
                        .build());
    }

    private String formatDuration(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        if (hours > 0) return hours + " hr " + minutes + " min";
        return minutes + " min";
    }

    private String toGeometryJson(OsrmResponse.OsrmGeometry geometry) {
        try {
            return objectMapper.writeValueAsString(geometry);
        } catch (Exception e) {
            return "";
        }
    }
}
