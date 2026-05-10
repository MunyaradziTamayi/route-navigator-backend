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
        LocalDateTime now = LocalDateTime.now();
        Worker worker = toEntity(request, null, now, now);
        return workerRepository.save(worker).map(this::toResponse);
    }

    public Mono<WorkerResponse> update(Long id, WorkerRequest request) {
        return workerRepository.findById(id)
                .flatMap(existing -> {
                    Worker worker = toEntity(
                            request,
                            id,
                            existing.getCreatedAt() == null ? LocalDateTime.now() : existing.getCreatedAt(),
                            LocalDateTime.now()
                    );
                    return workerRepository.save(worker);
                })
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
                .name(worker.getName())
                .email(worker.getEmail())
                .phone(worker.getPhone())
                .area(worker.getArea())
                .homeAddress(worker.getHomeAddress())
                .createdAt(worker.getCreatedAt())
                .updatedAt(worker.getUpdatedAt())
                .build();
    }
}
