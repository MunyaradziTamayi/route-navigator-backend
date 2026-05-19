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
@Table("geocoded_addresses")
public class GeocodedAddress {

    @Id
    private Long id;
    private String normalizedAddress;
    private String displayName;
    private Double lat;
    private Double lon;
    private LocalDateTime createdAt;
}
