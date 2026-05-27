package com.routenav.repository;

import com.routenav.model.WorkerMileageVisit;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface WorkerMileageVisitRepository extends ReactiveCrudRepository<WorkerMileageVisit, Long> {

    Flux<WorkerMileageVisit> findByStaffNumberOrderByVisitDateDescStartTimeDesc(String staffNumber);

    Flux<WorkerMileageVisit> findByReportIdOrderByStaffNumberAscVisitDateAscStartTimeAsc(Long reportId);

    Flux<WorkerMileageVisit> findByReportIdOrderByStaffNumberAscVisitDateAscStartTimeAscIdAsc(Long reportId);
}
