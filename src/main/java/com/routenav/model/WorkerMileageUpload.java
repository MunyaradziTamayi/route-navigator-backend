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
@Table("worker_mileage_uploads")
public class WorkerMileageUpload {

    @Id
    private Long id;
    private String fileName;
    private String fileHash;
    private LocalDateTime createdAt;
    // PROCESSING, COMPLETED or FAILED; null for uploads saved before statuses existed (treated as COMPLETED).
    private String status;
    private String errorMessage;
}
