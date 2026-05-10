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
@Table("workers")
public class Worker {

    @Id
    private Long id;
    private String name;
    private String email;
    private String phone;
    private String area;
    private String homeAddress;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
