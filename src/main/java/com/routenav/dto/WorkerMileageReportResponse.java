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
public class WorkerMileageReportResponse {

    private Long id;
    private Long uploadId;
    private String reportMonth;
    private String fileName;
    private String downloadUrl;
    private LocalDateTime createdAt;
    private List<WorkerMileageReportRowResponse> rows;
    private List<WorkerMileageVisitResponse> visits;
}
