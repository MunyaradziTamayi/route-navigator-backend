package com.routenav.controller;

import com.routenav.dto.ErrorResponse;
import com.routenav.dto.WorkerMileageReportResponse;
import com.routenav.dto.WorkerMileageTotalResponse;
import com.routenav.dto.WorkerMileageUploadResponse;
import com.routenav.dto.WorkerMileageVisitResponse;
import com.routenav.model.WorkerMileageReport;
import com.routenav.service.MileageUploadService;
import java.time.LocalDate;
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
import org.springframework.format.annotation.DateTimeFormat;
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
    public Mono<ResponseEntity<Object>> upload(@RequestPart("file") FilePart file,
                                               @RequestParam(name = "includeVisits", defaultValue = "true") boolean includeVisits,
                                               @RequestParam(name = "async", defaultValue = "false") boolean async) {
        // async=true returns straight away; poll GET /uploads/{id} until status is COMPLETED or FAILED.
        Mono<ResponseEntity<Object>> response = async
                ? mileageUploadService.startMonthlyMileageUpload(file)
                        .map(upload -> ResponseEntity.status("COMPLETED".equals(upload.getStatus()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                                .body((Object) upload))
                : mileageUploadService.calculateMonthlyMileageUpload(file, includeVisits)
                        .map(upload -> ResponseEntity.ok((Object) upload));
        return response
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }

    @GetMapping("/uploads")
    public Flux<WorkerMileageUploadResponse> listUploads(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return mileageUploadService.listUploads(date);
    }

    @GetMapping("/uploads/{id}")
    public Mono<ResponseEntity<WorkerMileageUploadResponse>> getUpload(
            @PathVariable("id") Long id,
            @RequestParam(name = "includeVisits", defaultValue = "true") boolean includeVisits) {
        return mileageUploadService.getUpload(id, includeVisits)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports")
    public Flux<WorkerMileageReportResponse> listReports(
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "year", required = false) Integer year) {
        return mileageUploadService.listReports(month, year);
    }

    @GetMapping("/totals")
    public Flux<WorkerMileageTotalResponse> getMileageTotals(
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "year", required = false) Integer year) {
        return mileageUploadService.getMileageTotals(month, year);
    }

    @GetMapping("/reports/{id}")
    public Mono<ResponseEntity<WorkerMileageReportResponse>> getReport(@PathVariable("id") Long id) {
        return mileageUploadService.getReport(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/visits/staff/{staffNumber}")
    public Flux<WorkerMileageVisitResponse> getVisitsByStaffNumber(@PathVariable("staffNumber") String staffNumber) {
        return mileageUploadService.getVisitsByStaffNumber(staffNumber);
    }

    @GetMapping("/reports/{id}/download")
    public Mono<ResponseEntity<Object>> downloadReport(@PathVariable("id") Long id) {
        return mileageUploadService.getReportFile(id)
                .map(this::toDownloadResponse)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/master/download")
    public Mono<ResponseEntity<Object>> downloadMasterReport(
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "year", required = false) Integer year) {
        return mileageUploadService.buildMasterMileageWorkbook(month, year)
                .map(workbook -> toDownloadResponse(workbook, "worker-mileage-master.xlsx"))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/routes/download")
    public Mono<ResponseEntity<Object>> downloadOptimisedRoutes(
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "year", required = false) Integer year) {
        return mileageUploadService.buildOptimisedRoutesWorkbook(month, year)
                .map(workbook -> toDownloadResponse(workbook, "worker-mileage-optimized-routes.xlsx"))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    private ResponseEntity<Object> toDownloadResponse(WorkerMileageReport report) {
        return toDownloadResponse(report.getFileContent(), report.getFileName());
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
}
