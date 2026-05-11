package com.routenav.model;

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
@Table("worker_mileage_report_rows")
public class WorkerMileageReportRow {

    @Id
    private Long id;
    private Long reportId;
    private String staffNumber;
    private Double totalMileage;
}
