package com.routenav.controller;

import com.routenav.dto.RouteRequest;
import com.routenav.dto.RouteResponse;
import com.routenav.dto.ErrorResponse;
import com.routenav.dto.GeocodeResponse;
import com.routenav.service.GeocodingService;
import com.routenav.service.RoutingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/route")
@RequiredArgsConstructor
public class RouteController {

    private final RoutingService routingService;

    private final GeocodingService geocodingService;

    @PostMapping
    public Mono<ResponseEntity<Object>> calculateRoute(@Valid @RequestBody RouteRequest request) {
        return resolveRouteRequest(request)
                .flatMap(routingService::calculateRoute)
                .map(response -> ResponseEntity.ok((Object) response))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }

    @GetMapping("/history")
    public Flux<RouteResponse> getRouteHistory() {
        return routingService.getSavedRoutes();
    }

    private Mono<RouteRequest> resolveRouteRequest(RouteRequest request) {
        Mono<ResolvedPoint> origin = resolvePoint(request.getOriginName(), request.getOriginLat(), request.getOriginLon(), "origin");
        Mono<ResolvedPoint> destination = resolvePoint(request.getDestinationName(), request.getDestinationLat(), request.getDestinationLon(), "destination");

        return Mono.zip(origin, destination)
                .map(points -> {
                    ResolvedPoint resolvedOrigin = points.getT1();
                    ResolvedPoint resolvedDestination = points.getT2();
                    request.setOriginName(resolvedOrigin.label());
                    request.setOriginLat(resolvedOrigin.lat());
                    request.setOriginLon(resolvedOrigin.lon());
                    request.setDestinationName(resolvedDestination.label());
                    request.setDestinationLat(resolvedDestination.lat());
                    request.setDestinationLon(resolvedDestination.lon());
                    return request;
                });
    }

    private Mono<ResolvedPoint> resolvePoint(String label, Double lat, Double lon, String fieldName) {
        if (lat != null && lon != null) {
            return Mono.just(new ResolvedPoint(label, lat, lon));
        }
        if (lat != null || lon != null) {
            return Mono.error(new IllegalArgumentException("Both lat and lon are required for " + fieldName + "."));
        }

        return geocodingService.geocodeFirst(label)
                .map(geocode -> new ResolvedPoint(displayLabel(label, geocode), geocode.getLat(), geocode.getLon()));
    }

    private String displayLabel(String fallback, GeocodeResponse geocode) {
        return geocode.getDisplayName() == null || geocode.getDisplayName().isBlank()
                ? fallback
                : geocode.getDisplayName();
    }

    private record ResolvedPoint(String label, Double lat, Double lon) {
    }
}
