package com.routenav.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerResponse {

    private Long id;
    private String name;
    private String email;
    private String phone;
    private String area;
    private String homeAddress;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
