package com.routenav.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerMileageUploadResponse {

    private Long id;
    private String fileName;
    private LocalDateTime createdAt;
    private String status;
    private String errorMessage;
    private Integer reportCount;
    private List<String> reportMonths;
    private List<WorkerMileageTotalResponse> totals;
    private List<WorkerMileageReportResponse> reports;
}
