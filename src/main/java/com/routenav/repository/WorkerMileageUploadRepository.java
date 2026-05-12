package com.routenav.repository;

import com.routenav.model.WorkerMileageUpload;
import java.time.LocalDateTime;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface WorkerMileageUploadRepository extends ReactiveCrudRepository<WorkerMileageUpload, Long> {

    Flux<WorkerMileageUpload> findAllByOrderByCreatedAtDesc();

    Flux<WorkerMileageUpload> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end);
}
