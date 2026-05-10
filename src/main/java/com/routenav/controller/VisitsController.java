package com.routenav.controller;

import com.routenav.dto.VisitRequest;
import com.routenav.dto.VisitResponse;
import com.routenav.service.VisitService;
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
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/visits")
@RequiredArgsConstructor
public class VisitsController {

    private final VisitService visitService;

    @GetMapping
    public Flux<VisitResponse> list() {
        return visitService.list();
    }

    @PostMapping
    public Mono<ResponseEntity<VisitResponse>> create(@Valid @RequestBody VisitRequest request) {
        return visitService.create(request).map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<VisitResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody VisitRequest request
    ) {
        return visitService.update(id, request)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return visitService.delete(id).thenReturn(ResponseEntity.noContent().build());
    }
}
