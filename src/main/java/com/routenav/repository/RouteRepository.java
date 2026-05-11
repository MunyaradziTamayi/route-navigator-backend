package com.routenav.repository;

import com.routenav.model.Route;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface RouteRepository extends ReactiveCrudRepository<Route, Long> {

    Flux<Route> findByOriginNameContainingIgnoreCase(String name);

    Flux<Route> findAllByOrderByCreatedAtDesc();

    Flux<Route> findAllByOriginLatAndOriginLonAndDestinationLatAndDestinationLonOrderByCreatedAtDesc(
            Double originLat,
            Double originLon,
            Double destinationLat,
            Double destinationLon
    );
}
