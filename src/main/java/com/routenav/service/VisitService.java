package com.routenav.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routenav.dto.VisitRequest;
import com.routenav.dto.VisitResponse;
import com.routenav.model.Visit;
import com.routenav.repository.VisitRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class VisitService {

    private final VisitRepository visitRepository;
    private final ObjectMapper objectMapper;

    public Flux<VisitResponse> list() {
        return visitRepository.findAllByOrderByCreatedAtDesc().map(this::toResponse);
    }

    public Mono<VisitResponse> create(VisitRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Visit visit = toEntity(request, null, now, now);
        return visitRepository.save(visit).map(this::toResponse);
    }

    public Mono<VisitResponse> update(Long id, VisitRequest request) {
        return visitRepository.findById(id)
                .flatMap(existing -> {
                    Visit visit = toEntity(
                            request,
                            id,
                            existing.getCreatedAt() == null ? LocalDateTime.now() : existing.getCreatedAt(),
                            LocalDateTime.now()
                    );
                    return visitRepository.save(visit);
                })
                .map(this::toResponse);
    }

    public Mono<Void> delete(Long id) {
        return visitRepository.deleteById(id);
    }

    private Visit toEntity(VisitRequest request, Long id, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return Visit.builder()
                .id(id)
                .originLabel(request.getOrigin().getLabel())
                .destinationLabel(request.getDestination().getLabel())
                .originLat(request.getOrigin().getLat())
                .originLon(request.getOrigin().getLon())
                .destinationLat(request.getDestination().getLat())
                .destinationLon(request.getDestination().getLon())
                .clientLabel(request.getClientLabel())
                .notes(request.getNotes())
                .scheduledDate(request.getScheduledDate())
                .workerIds(toJson(request.getWorkerIds()))
                .workerNames(toJson(request.getWorkerNames()))
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .visitsPerDay(request.getVisitsPerDay() == null ? 1 : Math.max(1, request.getVisitsPerDay()))
                .distanceKm(request.getDistanceKm())
                .durationSeconds(request.getDurationSeconds())
                .geometry(toJson(request.getGeoJson()))
                .status(request.getStatus() == null ? "pending" : request.getStatus())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private VisitResponse toResponse(Visit visit) {
        return VisitResponse.builder()
                .id(visit.getId())
                .originLabel(visit.getOriginLabel())
                .destinationLabel(visit.getDestinationLabel())
                .originLat(visit.getOriginLat())
                .originLon(visit.getOriginLon())
                .destinationLat(visit.getDestinationLat())
                .destinationLon(visit.getDestinationLon())
                .workerIds(fromJsonList(visit.getWorkerIds()))
                .workerNames(fromJsonList(visit.getWorkerNames()))
                .startTime(visit.getStartTime())
                .endTime(visit.getEndTime())
                .visitsPerDay(visit.getVisitsPerDay())
                .distanceKm(visit.getDistanceKm())
                .straightLineDistanceKm(straightLineDistanceKm(visit))
                .durationSeconds(visit.getDurationSeconds())
                .geoJson(fromJsonObject(visit.getGeometry()))
                .clientLabel(visit.getClientLabel())
                .notes(visit.getNotes())
                .scheduledDate(visit.getScheduledDate())
                .status(visit.getStatus())
                .createdAt(visit.getCreatedAt())
                .updatedAt(visit.getUpdatedAt())
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }

    private List<String> fromJsonList(String value) {
        try {
            if (value == null || value.isBlank()) return List.of();
            return objectMapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Object fromJsonObject(String value) {
        try {
            if (value == null || value.isBlank() || "null".equals(value)) return null;
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private double straightLineDistanceKm(Visit visit) {
        if (visit.getOriginLat() == null || visit.getOriginLon() == null
                || visit.getDestinationLat() == null || visit.getDestinationLon() == null) {
            return 0.0;
        }
        return haversineDistanceKm(visit.getOriginLat(), visit.getOriginLon(), visit.getDestinationLat(), visit.getDestinationLon());
    }

    private double haversineDistanceKm(double originLat, double originLon, double destinationLat, double destinationLon) {
        final double earthRadiusKm = 6371.0088;
        double latDistance = Math.toRadians(destinationLat - originLat);
        double lonDistance = Math.toRadians(destinationLon - originLon);
        double originLatRadians = Math.toRadians(originLat);
        double destinationLatRadians = Math.toRadians(destinationLat);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(originLatRadians) * Math.cos(destinationLatRadians)
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }
}
