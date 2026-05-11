package com.routenav.repository;

import com.routenav.model.WorkerMileageReportRow;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface WorkerMileageReportRowRepository extends ReactiveCrudRepository<WorkerMileageReportRow, Long> {

    Flux<WorkerMileageReportRow> findByReportIdOrderByStaffNumberAsc(Long reportId);
}
