package com.routenav.controller;

import com.routenav.dto.ComputeRouteRequest;
import com.routenav.dto.ComputeRouteResponse;
import com.routenav.dto.ErrorResponse;
import com.routenav.dto.GeocodeResponse;
import com.routenav.dto.RouteRequest;
import com.routenav.service.GeocodingService;
import com.routenav.service.RoutingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RoutesController {

    private final RoutingService routingService;

    private final GeocodingService geocodingService;

    @PostMapping("/compute")
    public Mono<ResponseEntity<Object>> compute(@Valid @RequestBody ComputeRouteRequest request) {
        return toRouteRequest(request)
                .flatMap(routingService::calculateRouteRaw)
                .map(body -> ResponseEntity.ok((Object) body))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }

    private Mono<RouteRequest> toRouteRequest(ComputeRouteRequest request) {
        Mono<ResolvedPoint> origin = resolvePoint(request.getOrigin(), "origin");
        Mono<ResolvedPoint> destination = resolvePoint(request.getDestination(), "destination");

        return Mono.zip(origin, destination)
                .map(points -> {
                    ResolvedPoint resolvedOrigin = points.getT1();
                    ResolvedPoint resolvedDestination = points.getT2();

                    RouteRequest backendRequest = new RouteRequest();
                    backendRequest.setOriginName(resolvedOrigin.label());
                    backendRequest.setDestinationName(resolvedDestination.label());
                    backendRequest.setOriginLat(resolvedOrigin.lat());
                    backendRequest.setOriginLon(resolvedOrigin.lon());
                    backendRequest.setDestinationLat(resolvedDestination.lat());
                    backendRequest.setDestinationLon(resolvedDestination.lon());
                    return backendRequest;
                });
    }

    private Mono<ResolvedPoint> resolvePoint(ComputeRouteRequest.Point point, String fieldName) {
        String label = point.getLabel() == null || point.getLabel().isBlank() ? defaultLabel(fieldName) : point.getLabel();
        if (point.getLat() != null && point.getLon() != null) {
            return Mono.just(new ResolvedPoint(label, point.getLat(), point.getLon()));
        }
        if (point.getLat() != null || point.getLon() != null) {
            return Mono.error(new IllegalArgumentException("Both lat and lon are required for " + fieldName + "."));
        }
        if (point.getLabel() == null || point.getLabel().isBlank()) {
            return Mono.error(new IllegalArgumentException("Address label is required for " + fieldName + " when lat/lon are not provided."));
        }

        return geocodingService.geocodeFirst(point.getLabel())
                .map(geocode -> new ResolvedPoint(displayLabel(point.getLabel(), geocode), geocode.getLat(), geocode.getLon()));
    }

    private String defaultLabel(String fieldName) {
        return "origin".equals(fieldName) ? "Origin" : "Destination";
    }

    private String displayLabel(String fallback, GeocodeResponse geocode) {
        return geocode.getDisplayName() == null || geocode.getDisplayName().isBlank()
                ? fallback
                : geocode.getDisplayName();
    }

    private record ResolvedPoint(String label, Double lat, Double lon) {
    }
}
