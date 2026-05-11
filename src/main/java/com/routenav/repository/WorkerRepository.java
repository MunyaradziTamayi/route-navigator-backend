package com.routenav.repository;

import com.routenav.model.Worker;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface WorkerRepository extends ReactiveCrudRepository<Worker, Long> {

    Flux<Worker> findByNameContainingIgnoreCase(String name);

    Mono<Worker> findByStaffNumber(String staffNumber);

    Flux<Worker> findAllByOrderByCreatedAtDesc();
}
