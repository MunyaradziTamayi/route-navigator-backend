package com.routenav.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerMileageReportRowResponse {

    private String staffNumber;
    private Double totalMileage;
    private Double totalMileageKm;
    private Double totalMileageMiles;
    private Double totalStraightLineMileage;
    private Double totalStraightLineMileageKm;
    private Double totalStraightLineMileageMiles;
}
