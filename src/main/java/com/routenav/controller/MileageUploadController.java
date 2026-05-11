package com.routenav.controller;

import com.routenav.dto.ErrorResponse;
import com.routenav.dto.WorkerMileageReportResponse;
import com.routenav.model.WorkerMileageReport;
import com.routenav.service.MileageUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/mileage")
@RequiredArgsConstructor
public class MileageUploadController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final MileageUploadService mileageUploadService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Object>> upload(@RequestPart("file") FilePart file) {
        return mileageUploadService.calculateMonthlyMileageReport(file)
                .map(report -> ResponseEntity.ok((Object) report))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }

    @GetMapping("/reports")
    public Flux<WorkerMileageReportResponse> listReports(@RequestParam(required = false) String month) {
        return mileageUploadService.listReports(month);
    }

    @GetMapping("/reports/{id}")
    public Mono<ResponseEntity<WorkerMileageReportResponse>> getReport(@PathVariable Long id) {
        return mileageUploadService.getReport(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/{id}/download")
    public Mono<ResponseEntity<Object>> downloadReport(@PathVariable Long id) {
        return mileageUploadService.getReportFile(id)
                .map(this::toDownloadResponse)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    private ResponseEntity<Object> toDownloadResponse(WorkerMileageReport report) {
        ByteArrayResource resource = new ByteArrayResource(report.getFileContent());
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .contentLength(report.getFileContent().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(report.getFileName())
                        .build()
                        .toString())
                .body((Object) resource);
    }
}
