package com.routenav.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerMileageTotalResponse {

    private String reportMonth;
    private Long uploadId;
    private String label;
    private Double totalMileageMiles;
    private Double totalStraightLineMileageMiles;
    private Integer reportCount;
    private List<WorkerMileageTotalRowResponse> rows;
}
