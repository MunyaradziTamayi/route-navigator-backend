package com.routenav.controller;

import com.routenav.dto.ErrorResponse;
import com.routenav.dto.WorkerRequest;
import com.routenav.dto.WorkerResponse;
import com.routenav.service.WorkerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
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
    public Mono<ResponseEntity<WorkerResponse>> getById(@PathVariable("id") Long id) {
        return workerService.getById(id).map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Mono<ResponseEntity<Object>> create(@Valid @RequestBody WorkerRequest request) {
        return workerService.create(request)
                .map(response -> ResponseEntity.ok((Object) response))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Object>> upload(@RequestPart("file") FilePart file) {
        return workerService.importWorkers(file)
                .map(workers -> ResponseEntity.ok((Object) workers))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())))
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<Object>> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody WorkerRequest request
    ) {
        return workerService.update(id, request)
                .map(response -> ResponseEntity.ok((Object) response))
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("id") Long id) {
        return workerService.delete(id).thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/search")
    public Flux<WorkerResponse> search(@RequestParam("name") String name) {
        return workerService.search(name);
    }
}
