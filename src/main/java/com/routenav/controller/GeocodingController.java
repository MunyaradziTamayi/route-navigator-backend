package com.routenav.controller;

import com.routenav.dto.GeocodeResponse;
import com.routenav.service.GeocodingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/geocode")
@RequiredArgsConstructor
public class GeocodingController {

    private final GeocodingService geocodingService;

    @GetMapping
    public Flux<GeocodeResponse> search(@RequestParam("q") String q) {
        return geocodingService.search(q);
    }
}
