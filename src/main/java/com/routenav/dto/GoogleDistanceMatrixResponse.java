package com.routenav.dto;

import java.util.List;
import lombok.Data;

@Data
public class GoogleDistanceMatrixResponse {

    private String status;
    private String errorMessage;
    private List<GoogleDistanceMatrixRow> rows;

    @Data
    public static class GoogleDistanceMatrixRow {
        private List<GoogleDistanceMatrixElement> elements;
    }

    @Data
    public static class GoogleDistanceMatrixElement {
        private String status;
        private GoogleDistanceMatrixValue distance;
    }

    @Data
    public static class GoogleDistanceMatrixValue {
        private Long value;
    }
}
