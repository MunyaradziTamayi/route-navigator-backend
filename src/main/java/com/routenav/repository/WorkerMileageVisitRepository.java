package com.routenav.repository;

import com.routenav.model.WorkerMileageVisit;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface WorkerMileageVisitRepository extends ReactiveCrudRepository<WorkerMileageVisit, Long> {

    // Visits are saved in optimised route order, so the id gives the route order within each day.
    Flux<WorkerMileageVisit> findByStaffNumberOrderByVisitDateDescIdDesc(String staffNumber);

    Flux<WorkerMileageVisit> findByReportIdOrderByStaffNumberAscVisitDateAscIdAsc(Long reportId);
}
