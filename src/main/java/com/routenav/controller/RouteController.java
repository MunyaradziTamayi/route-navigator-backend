package com.routenav.controller;

import com.routenav.dto.RouteRequest;
import com.routenav.dto.RouteResponse;
import com.routenav.service.RoutingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @PostMapping
    public Mono<ResponseEntity<RouteResponse>> calculateRoute(@Valid @RequestBody RouteRequest request) {
        return routingService.calculateRoute(request)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }

    @GetMapping("/history")
    public Flux<RouteResponse> getRouteHistory() {
        return routingService.getSavedRoutes();
    }
}

