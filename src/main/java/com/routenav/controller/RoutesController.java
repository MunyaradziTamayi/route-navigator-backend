package com.routenav.controller;

import com.routenav.dto.ComputeRouteRequest;
import com.routenav.dto.ComputeRouteResponse;
import com.routenav.dto.ErrorResponse;
import com.routenav.dto.RouteRequest;
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

    @PostMapping("/compute")
    public Mono<ResponseEntity<Object>> compute(@Valid @RequestBody ComputeRouteRequest request) {
        RouteRequest backendRequest = new RouteRequest();
        backendRequest.setOriginName(request.getOrigin().getLabel() == null ? "Origin" : request.getOrigin().getLabel());
        backendRequest.setDestinationName(request.getDestination().getLabel() == null ? "Destination" : request.getDestination().getLabel());
        backendRequest.setOriginLat(request.getOrigin().getLat());
        backendRequest.setOriginLon(request.getOrigin().getLon());
        backendRequest.setDestinationLat(request.getDestination().getLat());
        backendRequest.setDestinationLon(request.getDestination().getLon());

        return routingService.calculateRouteRaw(backendRequest)
                .map(body -> ResponseEntity.ok((Object) body))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }
}
