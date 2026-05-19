package com.routenav.repository;

import com.routenav.model.GeocodedAddress;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface GeocodedAddressRepository extends ReactiveCrudRepository<GeocodedAddress, Long> {

    Mono<GeocodedAddress> findByNormalizedAddress(String normalizedAddress);
}
