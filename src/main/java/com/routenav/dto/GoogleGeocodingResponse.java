package com.routenav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoogleGeocodingResponse {

    @JsonProperty("status")
    private String status;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("results")
    private List<GoogleGeocodingResult> results;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoogleGeocodingResult {

        @JsonProperty("formatted_address")
        private String formattedAddress;

        @JsonProperty("geometry")
        private GoogleGeometry geometry;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoogleGeometry {

        @JsonProperty("location")
        private GoogleLocation location;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoogleLocation {

        @JsonProperty("lat")
        private Double lat;

        @JsonProperty("lng")
        private Double lng;
    }
}
