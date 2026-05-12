package com.routenav.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routenav.dto.ComputeRouteResponse;
import com.routenav.dto.GoogleRoutesResponse;
import com.routenav.dto.JourneySummaryResponse;
import com.routenav.dto.RouteRequest;
import com.routenav.dto.RouteResponse;
import com.routenav.model.Route;
import com.routenav.repository.RouteRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class RoutingService {

    private final WebClient googleRoutesClient;

    private final String googleApiKey;

    private final RouteRepository routeRepository;

    private final ObjectMapper objectMapper;

    public RoutingService(@Qualifier("googleRoutesClient") WebClient googleRoutesClient,
                          @Value("${google.api-key}") String googleApiKey,
                          RouteRepository routeRepository,
                          ObjectMapper objectMapper) {
        this.googleRoutesClient = googleRoutesClient;
        this.googleApiKey = googleApiKey;
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
        return googleRoutesClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/directions/v2:computeRoutes")
                        .build())
                .header("X-Goog-Api-Key", requireGoogleApiKey())
                .header("X-Goog-FieldMask", "routes.distanceMeters,routes.duration,routes.staticDuration,routes.legs.distanceMeters,routes.legs.duration,routes.legs.staticDuration,routes.polyline.encodedPolyline")
                .bodyValue(buildGoogleRoutesRequest(request, true))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "Google Routes error (" + response.statusCode().value() + "): " + body))))
                .bodyToMono(GoogleRoutesResponse.class)
                .flatMap(google -> {
                    if (google.getRoutes() == null || google.getRoutes().isEmpty()) {
                        return Mono.error(new IllegalStateException("No routes found from Google Routes."));
                    }

                    GoogleRoutesResponse.GoogleRoute route = shortestRoute(google);
                    Long distanceMeters = distanceMetersOrZero(route);
                    Integer durationSecs = durationSecondsOrZero(route);
                    if (route.getPolyline() == null || !StringUtils.hasText(route.getPolyline().getEncodedPolyline())) {
                        return Mono.error(new IllegalStateException("Google Routes response missing distance/duration/polyline."));
                    }

                    final String geometry = toGeometryJson(toGeoJsonLineString(route.getPolyline().getEncodedPolyline()));

                    return routeRepository.save(buildRouteEntity(request, distanceMeters / 1000.0, durationSecs, geometry));
                });
    }

    private Mono<Route> fetchAndSaveDistanceOnlyRoute(RouteRequest request) {
        return googleRoutesClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/directions/v2:computeRoutes")
                        .build())
                .header("X-Goog-Api-Key", requireGoogleApiKey())
                .header("X-Goog-FieldMask", "routes.distanceMeters,routes.duration,routes.staticDuration,routes.legs.distanceMeters,routes.legs.duration,routes.legs.staticDuration")
                .bodyValue(buildGoogleRoutesRequest(request, false))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "Google Routes error (" + response.statusCode().value() + "): " + body))))
                .bodyToMono(GoogleRoutesResponse.class)
                .flatMap(google -> {
                    if (google.getRoutes() == null || google.getRoutes().isEmpty()) {
                        return Mono.error(new IllegalStateException("No routes found from Google Routes."));
                    }

                    GoogleRoutesResponse.GoogleRoute route = shortestRoute(google);
                    Long distanceMeters = distanceMetersOrZero(route);
                    Integer durationSecs = durationSecondsOrZero(route);

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

    private Map<String, Object> buildGoogleRoutesRequest(RouteRequest request, boolean includePolyline) {
        Map<String, Object> body = Map.of(
                "origin", waypoint(request.getOriginLat(), request.getOriginLon()),
                "destination", waypoint(request.getDestinationLat(), request.getDestinationLon()),
                "travelMode", "DRIVE",
                "routingPreference", "TRAFFIC_UNAWARE",
                "computeAlternativeRoutes", true);

        if (!includePolyline) {
            return body;
        }

        return Map.of(
                "origin", body.get("origin"),
                "destination", body.get("destination"),
                "travelMode", body.get("travelMode"),
                "routingPreference", body.get("routingPreference"),
                "computeAlternativeRoutes", body.get("computeAlternativeRoutes"),
                "polylineQuality", "HIGH_QUALITY",
                "polylineEncoding", "ENCODED_POLYLINE");
    }

    private Map<String, Object> waypoint(Double latitude, Double longitude) {
        return Map.of("location", Map.of("latLng", Map.of(
                "latitude", latitude,
                "longitude", longitude)));
    }

    private int parseGoogleDurationSeconds(String duration) {
        if (!StringUtils.hasText(duration) || !duration.endsWith("s")) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(duration.substring(0, duration.length() - 1)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Long distanceMeters(GoogleRoutesResponse.GoogleRoute route) {
        if (route.getDistanceMeters() != null) {
            return route.getDistanceMeters();
        }
        if (route.getLegs() == null || route.getLegs().isEmpty()) {
            return null;
        }

        long total = 0;
        boolean hasDistance = false;
        for (GoogleRoutesResponse.GoogleRouteLeg leg : route.getLegs()) {
            if (leg.getDistanceMeters() != null) {
                total += leg.getDistanceMeters();
                hasDistance = true;
            }
        }
        return hasDistance ? total : null;
    }

    private Integer durationSeconds(GoogleRoutesResponse.GoogleRoute route) {
        if (StringUtils.hasText(route.getDuration())) {
            return parseGoogleDurationSeconds(route.getDuration());
        }
        if (StringUtils.hasText(route.getStaticDuration())) {
            return parseGoogleDurationSeconds(route.getStaticDuration());
        }
        if (route.getLegs() == null || route.getLegs().isEmpty()) {
            return null;
        }

        int total = 0;
        boolean hasDuration = false;
        for (GoogleRoutesResponse.GoogleRouteLeg leg : route.getLegs()) {
            if (StringUtils.hasText(leg.getDuration())) {
                total += parseGoogleDurationSeconds(leg.getDuration());
                hasDuration = true;
            } else if (StringUtils.hasText(leg.getStaticDuration())) {
                total += parseGoogleDurationSeconds(leg.getStaticDuration());
                hasDuration = true;
            }
        }
        return hasDuration ? total : null;
    }

    private Long distanceMetersOrZero(GoogleRoutesResponse.GoogleRoute route) {
        Long distance = distanceMeters(route);
        return distance == null ? 0L : distance;
    }

    private Integer durationSecondsOrZero(GoogleRoutesResponse.GoogleRoute route) {
        Integer duration = durationSeconds(route);
        return duration == null ? 0 : duration;
    }

    private Map<String, Object> toGeoJsonLineString(String encodedPolyline) {
        return Map.of(
                "type", "LineString",
                "coordinates", decodePolyline(encodedPolyline).stream()
                        .map(point -> List.of(point.longitude(), point.latitude()))
                        .toList());
    }

    private List<LatLon> decodePolyline(String encodedPolyline) {
        List<LatLon> points = new ArrayList<>();
        int index = 0;
        int latitude = 0;
        int longitude = 0;

        while (index < encodedPolyline.length()) {
            DecodeResult latitudeResult = decodeNextValue(encodedPolyline, index);
            index = latitudeResult.nextIndex();
            latitude += latitudeResult.value();

            DecodeResult longitudeResult = decodeNextValue(encodedPolyline, index);
            index = longitudeResult.nextIndex();
            longitude += longitudeResult.value();

            points.add(new LatLon(latitude / 100000.0, longitude / 100000.0));
        }

        return points;
    }

    private DecodeResult decodeNextValue(String encodedPolyline, int startIndex) {
        int result = 0;
        int shift = 0;
        int index = startIndex;
        int b;

        do {
            b = encodedPolyline.charAt(index++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20 && index < encodedPolyline.length());

        int value = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
        return new DecodeResult(value, index);
    }

    private GoogleRoutesResponse.GoogleRoute shortestRoute(GoogleRoutesResponse response) {
        return response.getRoutes().stream()
                .filter(route -> distanceMeters(route) != null)
                .min(Comparator.comparing(this::distanceMeters))
                .orElse(response.getRoutes().get(0));
    }

    private String requireGoogleApiKey() {
        if (!StringUtils.hasText(googleApiKey)) {
            throw new IllegalStateException("GOOGLE_API_KEY environment variable is required.");
        }
        return googleApiKey;
    }

    private record LatLon(double latitude, double longitude) {
    }

    private record DecodeResult(int value, int nextIndex) {
    }
}
