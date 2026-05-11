package com.routenav.model;

import java.time.LocalDateTime;
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
@Table("worker_mileage_reports")
public class WorkerMileageReport {

    @Id
    private Long id;
    private String reportMonth;
    private String fileName;
    private byte[] fileContent;
    private LocalDateTime createdAt;
}
