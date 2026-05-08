package com.routenav.controller;

import com.routenav.dto.ComputeRouteResponse;
import com.routenav.dto.JourneySummaryResponse;
import com.routenav.service.RoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
public class JourneysController {

    private final RoutingService routingService;

    @GetMapping
    public Flux<JourneySummaryResponse> list(@RequestParam(defaultValue = "20") int limit) {
        return routingService.getJourneys(limit);
    }

    @GetMapping("/{id}")
    public Mono<ComputeRouteResponse> get(@PathVariable String id) {
        return routingService.getJourneyRoute(id);
    }
}

