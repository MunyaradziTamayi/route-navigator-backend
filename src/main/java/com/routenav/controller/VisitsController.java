package com.routenav.controller;

import com.routenav.dto.VisitRequest;
import com.routenav.dto.VisitResponse;
import com.routenav.service.VisitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.time.LocalDate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/visits")
@RequiredArgsConstructor
public class VisitsController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final VisitService visitService;

    @GetMapping
    public Flux<VisitResponse> list() {
        return visitService.list();
    }

    @PostMapping
    public Mono<ResponseEntity<VisitResponse>> create(@Valid @RequestBody VisitRequest request) {
        return visitService.create(request).map(ResponseEntity::ok);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Object>> upload(@RequestPart("file") FilePart file) {
        return visitService.upload(file)
                .map(visits -> ResponseEntity.ok((Object) visits))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest().body(e.getMessage())))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.getMessage())));
    }

    @PostMapping("/assign-staff")
    public Mono<ResponseEntity<Object>> assignStaff() {
        return visitService.assignStaff()
                .map(visits -> ResponseEntity.ok((Object) visits))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest().body(e.getMessage())))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.getMessage())));
    }

    @GetMapping("/schedule/download")
    public Mono<ResponseEntity<Object>> downloadSchedule(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return visitService.buildScheduleWorkbook(date)
                .map(workbook -> toDownloadResponse(workbook, scheduleFileName(date)))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest().body(e.getMessage())))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.getMessage())));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<VisitResponse>> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody VisitRequest request
    ) {
        return visitService.update(id, request)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("id") Long id) {
        return visitService.delete(id).thenReturn(ResponseEntity.noContent().build());
    }

    private ResponseEntity<Object> toDownloadResponse(byte[] workbook, String fileName) {
        ByteArrayResource resource = new ByteArrayResource(workbook);
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .contentLength(workbook.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName)
                        .build()
                        .toString())
                .body((Object) resource);
    }

    private String scheduleFileName(LocalDate date) {
        return date == null ? "visit-schedule.xlsx" : "visit-schedule-" + date + ".xlsx";
    }
}
