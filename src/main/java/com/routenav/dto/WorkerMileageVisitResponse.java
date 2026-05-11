package com.routenav.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerMileageVisitResponse {

    private Long id;
    private Long reportId;
    private String staffNumber;
    private LocalDate visitDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String address;
    private Double lat;
    private Double lon;
}
