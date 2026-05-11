package com.routenav.service;

import com.routenav.dto.WorkerRequest;
import com.routenav.dto.WorkerResponse;
import com.routenav.model.Worker;
import com.routenav.repository.WorkerRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class WorkerService {

    private final WorkerRepository workerRepository;

    public Flux<WorkerResponse> list() {
        return workerRepository.findAllByOrderByCreatedAtDesc().map(this::toResponse);
    }

    public Mono<WorkerResponse> getById(Long id) {
        return workerRepository.findById(id).map(this::toResponse);
    }

    public Mono<WorkerResponse> create(WorkerRequest request) {
        if (request.getStaffNumber() == null || request.getStaffNumber().isBlank()) {
            return Mono.error(new IllegalArgumentException("Staff number is required."));
        }

        LocalDateTime now = LocalDateTime.now();
        Worker worker = toEntity(request, null, now, now);
        return ensureStaffNumberAvailable(request.getStaffNumber(), null)
                .then(workerRepository.save(worker))
                .map(this::toResponse);
    }

    public Mono<WorkerResponse> update(Long id, WorkerRequest request) {
        if (request.getStaffNumber() == null || request.getStaffNumber().isBlank()) {
            return Mono.error(new IllegalArgumentException("Staff number is required."));
        }

        return workerRepository.findById(id)
                .flatMap(existing -> ensureStaffNumberAvailable(request.getStaffNumber(), id).then(Mono.defer(() -> {
                    Worker worker = toEntity(
                            request,
                            id,
                            existing.getCreatedAt() == null ? LocalDateTime.now() : existing.getCreatedAt(),
                            LocalDateTime.now()
                    );
                    return workerRepository.save(worker);
                })))
                .map(this::toResponse);
    }

    public Mono<Void> delete(Long id) {
        return workerRepository.deleteById(id);
    }

    public Flux<WorkerResponse> search(String name) {
        return workerRepository.findByNameContainingIgnoreCase(name).map(this::toResponse);
    }

    private Worker toEntity(WorkerRequest request, Long id, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return Worker.builder()
                .id(id)
                .staffNumber(request.getStaffNumber().trim())
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .area(request.getArea())
                .homeAddress(request.getHomeAddress())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private WorkerResponse toResponse(Worker worker) {
        return WorkerResponse.builder()
                .id(worker.getId())
                .staffNumber(worker.getStaffNumber())
                .name(worker.getName())
                .email(worker.getEmail())
                .phone(worker.getPhone())
                .area(worker.getArea())
                .homeAddress(worker.getHomeAddress())
                .createdAt(worker.getCreatedAt())
                .updatedAt(worker.getUpdatedAt())
                .build();
    }

    private Mono<Void> ensureStaffNumberAvailable(String staffNumber, Long currentWorkerId) {
        return workerRepository.findByStaffNumber(staffNumber.trim())
                .filter(worker -> currentWorkerId == null || !currentWorkerId.equals(worker.getId()))
                .flatMap(worker -> Mono.error(new IllegalArgumentException("Staff number already exists: " + staffNumber)))
                .then();
    }
}
