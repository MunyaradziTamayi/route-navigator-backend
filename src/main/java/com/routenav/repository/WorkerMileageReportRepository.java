package com.routenav.repository;

import com.routenav.model.WorkerMileageReport;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface WorkerMileageReportRepository extends ReactiveCrudRepository<WorkerMileageReport, Long> {

    Flux<WorkerMileageReport> findAllByOrderByCreatedAtDesc();

    Flux<WorkerMileageReport> findByReportMonthOrderByCreatedAtDesc(String reportMonth);

    Flux<WorkerMileageReport> findByReportMonthStartingWithOrderByCreatedAtDesc(String reportMonthPrefix);

    Flux<WorkerMileageReport> findByUploadIdOrderByReportMonthAsc(Long uploadId);

    Mono<Void> deleteByUploadId(Long uploadId);
}
