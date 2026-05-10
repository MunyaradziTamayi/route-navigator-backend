package com.routenav.controller;

import com.routenav.dto.WorkerRequest;
import com.routenav.dto.WorkerResponse;
import com.routenav.service.WorkerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/workers")
@RequiredArgsConstructor
public class WorkersController {

    private final WorkerService workerService;

    @GetMapping
    public Flux<WorkerResponse> list() {
        return workerService.list();
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<WorkerResponse>> getById(@PathVariable Long id) {
        return workerService.getById(id).map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Mono<ResponseEntity<WorkerResponse>> create(@Valid @RequestBody WorkerRequest request) {
        return workerService.create(request).map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<WorkerResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody WorkerRequest request
    ) {
        return workerService.update(id, request)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return workerService.delete(id).thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/search")
    public Flux<WorkerResponse> search(@RequestParam String name) {
        return workerService.search(name);
    }
}
