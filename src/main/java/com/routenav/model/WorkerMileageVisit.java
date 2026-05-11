package com.routenav.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("worker_mileage_visits")
public class WorkerMileageVisit {

    @Id
    private Long id;
    private Long reportId;
    private String staffNumber;
    private LocalDate visitDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String address;
    private Double lat;
    private Double lon;
    private LocalDateTime createdAt;
}
