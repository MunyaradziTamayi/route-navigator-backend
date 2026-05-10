package com.routenav.repository;

import com.routenav.model.Visit;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface VisitRepository extends ReactiveCrudRepository<Visit, Long> {

    Flux<Visit> findAllByOrderByCreatedAtDesc();
}
