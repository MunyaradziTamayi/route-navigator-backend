package com.routenav.service;

import com.routenav.dto.WorkerMileageReportResponse;
import com.routenav.dto.WorkerMileageReportRowResponse;
import com.routenav.dto.WorkerMileageTotalResponse;
import com.routenav.dto.WorkerMileageTotalRowResponse;
import com.routenav.dto.WorkerMileageUploadResponse;
import com.routenav.dto.WorkerMileageVisitResponse;
import com.routenav.dto.GoogleDistanceMatrixResponse;
import com.routenav.model.WorkerMileageReport;
import com.routenav.model.WorkerMileageReportRow;
import com.routenav.model.WorkerMileageUpload;
import com.routenav.model.WorkerMileageVisit;
import com.routenav.repository.WorkerMileageReportRepository;
import com.routenav.repository.WorkerMileageReportRowRepository;
import com.routenav.repository.WorkerMileageUploadRepository;
import com.routenav.repository.WorkerMileageVisitRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HexFormat;
import java.util.Iterator;
import com.github.pjfanning.xlsx.StreamingReader;
import com.github.pjfanning.xlsx.exceptions.ExcelRuntimeException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Service
public class MileageUploadService {

    private static final Logger log = LoggerFactory.getLogger(MileageUploadService.class);

    private static final double KM_TO_MILES = 0.621371;
    private static final double METERS_PER_KM = 1000.0;
    private static final int EXACT_ROUTE_LIMIT = 12;
    private static final Pattern UK_POSTCODE = Pattern.compile("([A-Z]{1,2}\\d[A-Z\\d]?)\\s*([\\dO][A-Z]{2})$", Pattern.CASE_INSENSITIVE);
    // Visit times are ignored when optimising, so each carer's whole day is routed as one group.
    private static final String DAY_ROUTE = "Day";
    private static final int BATCH_MAX_DIM = 25;
    private static final int BATCH_MAX_ELEM = 100;
    private static final int ROUTE_MATRIX_CONCURRENCY = 4;
    private static final int CACHE_QUERY_CHUNK = 500;
    private static final int REPORT_ROW_WINDOW = 200;
    private static final String UPLOAD_PROCESSING = "PROCESSING";
    private static final String UPLOAD_COMPLETED = "COMPLETED";
    private static final String UPLOAD_FAILED = "FAILED";

    private static final Map<String, List<String>> COLUMN_ALIASES = Map.of(
            "Carer_No", List.of("Carer_No", "Carer No", "CarerNo", "Carer Number", "Staff Number", "Staff No", "StaffNo", "Worker Number", "Worker No", "WorkerNo", "Employee No", "Employee Number"),
            "Client Address", List.of("Client Address", "Client_Address", "ClientAddress", "Address", "Location", "Visit Address", "Client Location"),
            "Date_of_Call", List.of("Date_of_Call", "Date of Call", "DateofCall", "Date", "Call Date", "Visit Date", "Service Date"),
            "Time_of_Call", List.of("Time_of_Call", "Time of Call", "TimeofCall", "Time", "Visit Time", "Call Time", "Visit_Time"),
            "Name", List.of("Name", "Client Name", "ClientName", "Client", "Patient Name", "Service User"),
            "Shifts", List.of("Shifts", "Shift", "Call Type", "Period", "Run"),
            "From Address", List.of("From Address", "From_Address", "FromAddress", "From", "Origin", "Start", "Start Address", "Pickup", "Pickup Address"),
            "To Address", List.of("To Address", "To_Address", "ToAddress", "To", "Destination", "End", "End Address", "Dropoff", "Dropoff Address")
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("M-d-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yy"),
            DateTimeFormatter.ofPattern("M/d/yy"),
            DateTimeFormatter.ofPattern("d-M-yy"),
            DateTimeFormatter.ofPattern("M-d-yy")
    );

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH)
    );

    private final GeocodingService geocodingService;

    private final RoutingService routingService;

    private final WorkerMileageReportRepository reportRepository;

    private final WorkerMileageReportRowRepository reportRowRepository;

    private final WorkerMileageUploadRepository uploadRepository;

    private final WorkerMileageVisitRepository visitRepository;

    private final DatabaseClient databaseClient;

    private final WebClient googleRoutesClient;

    private final String googleApiKey;

    private final Duration routeMatrixBatchDelay;

    private final int routeMatrixElementsPerMinute;

    private final Object routeMatrixQuotaLock = new Object();

    private long routeMatrixNextSlotNanos = System.nanoTime();

    private final Map<Long, Mono<Void>> runningUploads = new ConcurrentHashMap<>();

    public MileageUploadService(GeocodingService geocodingService,
                                RoutingService routingService,
                                WorkerMileageReportRepository reportRepository,
                                WorkerMileageReportRowRepository reportRowRepository,
                                WorkerMileageUploadRepository uploadRepository,
                                WorkerMileageVisitRepository visitRepository,
                                DatabaseClient databaseClient,
                                @Qualifier("googleRoutesClient") WebClient googleRoutesClient,
                                @Value("${google.api-key}") String googleApiKey,
                                @Value("${google.route-matrix-batch-delay-ms:1000}") long routeMatrixBatchDelayMs,
                                @Value("${google.route-matrix-elements-per-minute:2500}") int routeMatrixElementsPerMinute) {
        this.geocodingService = geocodingService;
        this.routingService = routingService;
        this.reportRepository = reportRepository;
        this.reportRowRepository = reportRowRepository;
        this.uploadRepository = uploadRepository;
        this.visitRepository = visitRepository;
        this.databaseClient = databaseClient;
        this.googleRoutesClient = googleRoutesClient;
        this.googleApiKey = googleApiKey;
        this.routeMatrixBatchDelay = Duration.ofMillis(Math.max(0, routeMatrixBatchDelayMs));
        this.routeMatrixElementsPerMinute = Math.max(1, routeMatrixElementsPerMinute);
    }

    public Mono<List<WorkerMileageReportResponse>> calculateMonthlyMileageReports(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> calculateMonthlyMileageReports(bytes, null));
    }

    private Mono<List<WorkerMileageReportResponse>> calculateMonthlyMileageReports(byte[] bytes, Long uploadId) {
        return calculateMonthlyMileageReports(bytes, uploadId, true);
    }

    private Mono<List<WorkerMileageReportResponse>> calculateMonthlyMileageReports(byte[] bytes, Long uploadId, boolean includeVisits) {
        return Mono.fromCallable(() -> parseVisits(bytes))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(visits -> Flux.fromIterable(groupVisitsByMonth(visits).entrySet()))
                .concatMap(entry -> calculateMileage(entry.getValue())
                        .flatMap(analysis -> Mono.fromCallable(() -> buildWorkbook(analysis))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(workbook -> saveReport(uploadId, reportMonth(entry.getKey()), optimisedVisits(analysis), analysis.rows(), workbook, includeVisits))
                                .flatMap(report -> logMileageResults(uploadId, reportMonth(entry.getKey()), analysis).thenReturn(report))))
                .collectList();
    }

    // Logs each carer's monthly totals so results show in the server logs. Straight-line miles follow the uploaded
    // visit order within each day; logging problems never fail the upload.
    private Mono<Void> logMileageResults(Long uploadId, String reportMonth, AnalysisResult analysis) {
        return Flux.fromIterable(analysis.visits())
                .map(VisitRow::address)
                .filter(StringUtils::hasText)
                .distinct()
                .flatMap(address -> geocodingService.geocodeFirst(address)
                        .filter(response -> response.getLat() != null && response.getLon() != null)
                        .map(response -> Map.entry(address, new Location(response.getLat(), response.getLon())))
                        .onErrorResume(error -> Mono.empty()), 4)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .doOnNext(locations -> {
                    Map<String, Double> straightLineKmByStaff = new HashMap<>();
                    for (ShiftResult shift : analysis.shifts()) {
                        straightLineKmByStaff.merge(shift.key().staffNumber(), straightLineShiftKm(shift.originalVisits(), locations), Double::sum);
                    }

                    double totalOriginalKm = 0.0;
                    double totalOptimisedKm = 0.0;
                    double totalStraightLineKm = 0.0;
                    for (MileageResult row : analysis.rows()) {
                        double straightLineKm = straightLineKmByStaff.getOrDefault(row.staffNumber(), 0.0);
                        log.info("Upload {} {} carer {}: original {} mi, optimised {} mi, saved {} mi ({}%), straight-line {} mi",
                                uploadId, reportMonth, row.staffNumber(),
                                kmToMiles(row.totalOriginalMileageKm()), kmToMiles(row.totalMileageKm()),
                                kmToMiles(row.totalSavedMileageKm()), savedPercent(row.totalOriginalMileageKm(), row.totalSavedMileageKm()),
                                kmToMiles(straightLineKm));
                        totalOriginalKm += row.totalOriginalMileageKm();
                        totalOptimisedKm += row.totalMileageKm();
                        totalStraightLineKm += straightLineKm;
                    }

                    long unique = analysis.visits().stream().map(VisitRow::address).filter(StringUtils::hasText).distinct().count();
                    log.info("Upload {} {} total for {} carer(s): original {} mi, optimised {} mi, saved {} mi ({}%), straight-line {} mi ({} of {} addresses located)",
                            uploadId, reportMonth, analysis.rows().size(),
                            kmToMiles(totalOriginalKm), kmToMiles(totalOptimisedKm),
                            kmToMiles(totalOriginalKm - totalOptimisedKm), savedPercent(totalOriginalKm, totalOriginalKm - totalOptimisedKm),
                            kmToMiles(totalStraightLineKm), locations.size(), unique);
                })
                .onErrorResume(error -> {
                    log.warn("Could not log mileage results for upload {} {}: {}", uploadId, reportMonth, error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private double straightLineShiftKm(List<VisitRow> visits, Map<String, Location> locations) {
        double total = 0.0;
        for (int i = 1; i < visits.size(); i++) {
            Location origin = locations.get(visits.get(i - 1).address());
            Location destination = locations.get(visits.get(i).address());
            if (origin != null && destination != null) {
                total += straightLineDistanceKm(origin, destination);
            }
        }
        return total;
    }

    private double savedPercent(double originalKm, double savedKm) {
        return originalKm > 0.0 ? roundMileage(savedKm / originalKm * 100.0) : 0.0;
    }

    public Mono<List<WorkerMileageTotalResponse>> calculateMonthlyMileageTotals(FilePart file) {
        return calculateMonthlyMileageUpload(file)
                .map(WorkerMileageUploadResponse::getTotals);
    }

    public Mono<WorkerMileageUploadResponse> calculateMonthlyMileageUpload(FilePart file) {
        return calculateMonthlyMileageUpload(file, true);
    }

    // Waits for the background calculation. A dropped connection only stops the wait: the calculation finishes
    // and uploading the same file again returns the result (or rejoins the calculation if it is still running).
    public Mono<WorkerMileageUploadResponse> calculateMonthlyMileageUpload(FilePart file, boolean includeVisits) {
        return startUpload(file)
                .flatMap(job -> job.completion()
                        .then(Mono.defer(() -> getUpload(job.upload().getId(), includeVisits))));
    }

    // Returns as soon as the calculation has started; poll getUpload until the status is COMPLETED or FAILED.
    public Mono<WorkerMileageUploadResponse> startMonthlyMileageUpload(FilePart file) {
        return startUpload(file)
                .flatMap(job -> getUpload(job.upload().getId(), false));
    }

    private Mono<UploadJob> startUpload(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> {
                    String fileHash = sha256Hex(bytes);
                    return uploadRepository.findFirstByFileHashOrderByCreatedAtDesc(fileHash)
                            .flatMap(this::reuseUpload)
                            .switchIfEmpty(Mono.defer(() -> saveUpload(file.filename(), fileHash)
                                    .map(upload -> new UploadJob(upload, runUpload(upload.getId(), bytes)))));
                });
    }

    private Mono<UploadJob> reuseUpload(WorkerMileageUpload existingUpload) {
        Mono<Void> running = runningUploads.get(existingUpload.getId());
        if (running != null) {
            return Mono.just(new UploadJob(existingUpload, running));
        }

        // Re-read after checking the running jobs: a job that just finished has already saved its final status.
        return uploadRepository.findById(existingUpload.getId())
                .flatMap(upload -> reportRepository.findByUploadIdOrderByReportMonthAsc(upload.getId())
                        .hasElements()
                        .flatMap(hasReports -> {
                            if (hasReports && !UPLOAD_FAILED.equals(upload.getStatus()) && !UPLOAD_PROCESSING.equals(upload.getStatus())) {
                                return Mono.just(new UploadJob(upload, Mono.empty()));
                            }
                            // PROCESSING without a running job means the server restarted mid-calculation.
                            log.warn("Upload {} for file {} is {} with no running calculation; recalculating",
                                    upload.getId(), upload.getFileName(), upload.getStatus() == null ? "missing reports" : upload.getStatus());
                            return cleanupFailedUpload(upload.getId()).then(Mono.<UploadJob>empty());
                        }));
    }

    private Mono<Void> runUpload(Long uploadId, byte[] bytes) {
        Sinks.Empty<Void> done = Sinks.empty();
        Mono<Void> completion = done.asMono();
        runningUploads.put(uploadId, completion);

        // Subscribed here rather than by the request, so a dropped connection cannot cancel it.
        calculateMonthlyMileageReports(bytes, uploadId, false)
                .then(updateUploadStatus(uploadId, UPLOAD_COMPLETED, null))
                .onErrorResume(error -> reportRepository.deleteByUploadId(uploadId)
                        .then(updateUploadStatus(uploadId, UPLOAD_FAILED, error.getMessage()))
                        .then(Mono.error(error)))
                .doFinally(signal -> runningUploads.remove(uploadId))
                .subscribe(
                        null,
                        error -> {
                            log.warn("Mileage upload {} failed: {}", uploadId, error.getMessage());
                            done.tryEmitError(error);
                        },
                        done::tryEmitEmpty);
        return completion;
    }

    private Mono<Void> updateUploadStatus(Long uploadId, String status, String errorMessage) {
        return uploadRepository.findById(uploadId)
                .flatMap(upload -> {
                    upload.setStatus(status);
                    upload.setErrorMessage(errorMessage == null ? null
                            : errorMessage.substring(0, Math.min(errorMessage.length(), 2000)));
                    return uploadRepository.save(upload);
                })
                .then();
    }

    public Flux<WorkerMileageReportResponse> listReports(String reportMonth, Integer year, boolean includeVisits) {
        // One report at a time: building every report's visits at once exhausts the 512MB instance.
        return findReports(reportMonth, year).concatMap(report -> toReportResponse(report, includeVisits));
    }

    public Flux<WorkerMileageTotalResponse> getMileageTotals(String reportMonth, Integer year) {
        return latestReports(reportMonth, year)
                .concatMap(report -> Mono.zip(
                                reportRowRepository.findByReportIdOrderByStaffNumberAsc(report.getId()).collectList(),
                                visitRepository.findByReportIdOrderByStaffNumberAscVisitDateAscIdAsc(report.getId()).collectList())
                        .map(tuple -> new ReportRows(report, tuple.getT1(), tuple.getT2())))
                .collectList()
                .flatMapMany(reportRows -> {
                    if (reportRows.isEmpty()) {
                        return Flux.empty();
                    }

                    List<ReportRows> latestReportRows = latestReportRowsByMonth(reportRows);
                    Map<String, List<ReportRows>> reportsByMonth = new TreeMap<>();
                    for (ReportRows rows : latestReportRows) {
                        reportsByMonth.computeIfAbsent(rows.report().getReportMonth(), ignored -> new ArrayList<>()).add(rows);
                    }

                    List<WorkerMileageTotalResponse> totals = new ArrayList<>();
                    totals.add(toStoredMileageTotalResponse("all", "Total mileage for all selected months", latestReportRows));
                    for (Map.Entry<String, List<ReportRows>> entry : reportsByMonth.entrySet()) {
                        totals.add(toStoredMileageTotalResponse(entry.getKey(), monthLabel(entry.getKey()), entry.getValue()));
                    }
                    return Flux.fromIterable(totals);
                });
    }

    public Flux<WorkerMileageUploadResponse> listUploads(LocalDate uploadDate, boolean includeVisits) {
        Flux<WorkerMileageUpload> uploads = uploadDate == null
                ? uploadRepository.findAllByOrderByCreatedAtDesc()
                : uploadRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(uploadDate.atStartOfDay(), uploadDate.plusDays(1).atStartOfDay());

        // One upload at a time: building every upload's visits at once exhausts the 512MB instance.
        return uploads.concatMap(upload -> getUpload(upload.getId(), includeVisits)
                .defaultIfEmpty(toUploadResponse(upload, List.of(), false)));
    }

    public Mono<WorkerMileageUploadResponse> getUpload(Long uploadId) {
        return getUpload(uploadId, true);
    }

    public Mono<WorkerMileageUploadResponse> getUpload(Long uploadId, boolean includeVisits) {
        return uploadRepository.findById(uploadId)
                .flatMap(upload -> reportRepository.findByUploadIdOrderByReportMonthAsc(uploadId)
                        .flatMap(report -> toReportResponse(report, includeVisits))
                        .collectList()
                        .map(reports -> toUploadResponse(upload, reports, true)));
    }

    private Mono<WorkerMileageUpload> saveUpload(String fileName, String fileHash) {
        return uploadRepository.save(WorkerMileageUpload.builder()
                .fileName(fileName == null || fileName.isBlank() ? "worker-mileage-upload.xlsx" : fileName)
                .fileHash(fileHash)
                .createdAt(LocalDateTime.now())
                .status(UPLOAD_PROCESSING)
                .build());
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 hashing is not available.", e);
        }
    }

    private Flux<WorkerMileageReport> findReports(String reportMonth, Integer year) {
        ReportMonthFilter filter = parseReportMonthFilter(reportMonth, year);
        Flux<WorkerMileageReport> reports;
        if (filter == null) {
            reports = reportRepository.findAllByOrderByCreatedAtDesc();
        } else if (filter.exactReportMonth() != null) {
            reports = reportRepository.findByReportMonthOrderByCreatedAtDesc(filter.exactReportMonth());
        } else if (filter.year() != null) {
            reports = reportRepository.findByReportMonthStartingWithOrderByCreatedAtDesc(filter.year() + "-");
        } else {
            reports = reportRepository.findAllByOrderByCreatedAtDesc();
        }

        if (filter != null && filter.month() != null && filter.exactReportMonth() == null) {
            reports = reports.filter(report -> reportMonthMatches(report.getReportMonth(), filter.month()));
        }

        return reports;
    }

    // Only the newest report per month counts, so pick those before loading any rows or visits.
    private Flux<WorkerMileageReport> latestReports(String reportMonth, Integer year) {
        return findReports(reportMonth, year)
                .collectList()
                .flatMapIterable(reports -> {
                    Map<String, WorkerMileageReport> latestByMonth = new TreeMap<>();
                    for (WorkerMileageReport report : reports) {
                        WorkerMileageReport current = latestByMonth.get(report.getReportMonth());
                        if (current == null || isNewerReport(report, current)) {
                            latestByMonth.put(report.getReportMonth(), report);
                        }
                    }
                    return latestByMonth.values();
                });
    }

    public Mono<WorkerMileageReportResponse> getReport(Long reportId) {
        return reportRepository.findById(reportId).flatMap(this::toReportResponse);
    }

    public Mono<WorkerMileageReport> getReportFile(Long reportId) {
        return reportRepository.findById(reportId);
    }

    public Mono<byte[]> buildMasterMileageWorkbook(String reportMonth, Integer year) {
        return latestReports(reportMonth, year)
                .concatMap(report -> Mono.zip(
                                reportRowRepository.findByReportIdOrderByStaffNumberAsc(report.getId()).collectList(),
                                visitRepository.findByReportIdOrderByStaffNumberAscVisitDateAscIdAsc(report.getId()).collectList())
                        .map(tuple -> new ReportRows(report, tuple.getT1(), tuple.getT2())))
                .collectList()
                .flatMap(reportRows -> {
                    if (reportRows.isEmpty()) {
                        return Mono.empty();
                    }
                    List<ReportRows> latestReportRows = latestReportRowsByMonth(reportRows);
                    return Mono.fromCallable(() -> buildMasterMileageWorkbook(latestReportRows))
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    public Mono<byte[]> buildOptimisedRoutesWorkbook(String reportMonth, Integer year) {
        return latestReports(reportMonth, year)
                .concatMap(report -> visitRepository.findByReportIdOrderByStaffNumberAscVisitDateAscIdAsc(report.getId())
                        .collectList()
                        .map(visits -> new ReportVisits(report, visits)))
                .collectList()
                .flatMap(reportVisits -> {
                    if (reportVisits.isEmpty()) {
                        return Mono.empty();
                    }
                    List<ReportVisits> latestReportVisits = latestReportVisitsByMonth(reportVisits);
                    return Mono.fromCallable(() -> buildOptimisedRoutesWorkbook(latestReportVisits))
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    public Flux<WorkerMileageVisitResponse> getVisitsByStaffNumber(String staffNumber) {
        return visitRepository.findByStaffNumberOrderByVisitDateDescIdDesc(staffNumber)
                .collectList()
                .flatMapMany(visits -> toVisitResponsesWithMileage(visits, false));
    }

    private byte[] toBytes(DataBuffer dataBuffer) {
        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);
        DataBufferUtils.release(dataBuffer);
        return bytes;
    }

    private List<VisitRow> parseVisits(byte[] bytes) throws IOException {
        // Stream rows instead of loading the whole sheet: large uploads otherwise exhaust the heap.
        try (Workbook workbook = StreamingReader.builder()
                .rowCacheSize(100)
                .bufferSize(4096)
                .open(new ByteArrayInputStream(bytes))) {
            Iterator<Row> rows = workbook.getSheetAt(0).rowIterator();
            if (!rows.hasNext()) {
                throw new IllegalArgumentException("The Excel file is empty.");
            }
            Row header = rows.next();

            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = findColumns(header, formatter);
            List<VisitRow> visits = new ArrayList<>();
            List<String> skippedRows = new ArrayList<>();

            while (rows.hasNext()) {
                Row row = rows.next();
                if (isBlankRow(row, formatter)) {
                    continue;
                }

                // Excel row numbers are 1-based.
                int excelRow = row.getRowNum() + 1;
                String staffNumber = getText(row, columns.get("Carer_No"), formatter);
                String address = getText(row, columns.get("Client Address"), formatter);
                if (staffNumber.isBlank() || address.isBlank()) {
                    skippedRows.add("Row " + excelRow + ": staff number and address are required.");
                    continue;
                }

                LocalDate date;
                TimeRange visitTime;
                try {
                    date = parseDate(row.getCell(columns.get("Date_of_Call")), formatter);
                    visitTime = parseTimeRange(row.getCell(columns.get("Time_of_Call")), formatter);
                } catch (IllegalArgumentException e) {
                    skippedRows.add("Row " + excelRow + ": " + e.getMessage());
                    continue;
                }
                String name = columns.containsKey("Name") ? getText(row, columns.get("Name"), formatter) : "";
                String shift = columns.containsKey("Shifts") ? normalizeShift(getText(row, columns.get("Shifts"), formatter), visitTime.startTime()) : shiftForTime(visitTime.startTime());

                visits.add(new VisitRow(staffNumber, LocalDateTime.of(date, visitTime.startTime()), date, visitTime.startTime(), visitTime.endTime(), address, name, shift, null, null));
            }

            if (!skippedRows.isEmpty()) {
                log.warn("Skipped {} invalid row(s) in mileage upload: {}", skippedRows.size(),
                        String.join(" ", skippedRows.subList(0, Math.min(20, skippedRows.size()))));
            }

            if (visits.isEmpty()) {
                String reason = skippedRows.isEmpty() ? "" : " First problem: " + skippedRows.get(0);
                throw new IllegalArgumentException("No valid visit rows were found in the Excel file." + reason);
            }

            return visits;
        } catch (ExcelRuntimeException e) {
            throw new IllegalArgumentException("The file could not be read as an Excel (.xlsx) workbook.", e);
        }
    }

    private Map<String, Integer> findColumns(Row header, DataFormatter formatter) {
        Map<String, Integer> actualByKey = new HashMap<>();
        for (Cell cell : header) {
            String value = formatter.formatCellValue(cell).trim();
            if (!value.isBlank()) {
                actualByKey.put(columnKey(value), cell.getColumnIndex());
            }
        }

        Map<String, Integer> columns = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : COLUMN_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                Integer index = actualByKey.get(columnKey(alias));
                if (index != null) {
                    columns.put(entry.getKey(), index);
                    break;
                }
            }
        }

        for (String required : List.of("Carer_No", "Date_of_Call", "Time_of_Call", "Client Address")) {
            if (!columns.containsKey(required)) {
                throw new IllegalArgumentException("Input missing required columns: " + required + ". Found: " + actualByKey.keySet());
            }
        }
        return columns;
    }

    private String columnKey(String value) {
        return value.toUpperCase(Locale.ENGLISH).replace(" ", "").replace("_", "");
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).trim().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String getText(Row row, int column, DataFormatter formatter) {
        Cell cell = row.getCell(column);
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private LocalDate parseDate(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            throw new IllegalArgumentException("Date is required.");
        }

        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }

        String value = formatter.formatCellValue(cell).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Date is required.");
        }
        for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, dateFormatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        throw new IllegalArgumentException("Invalid date value: " + value);
    }

    private TimeRange parseTimeRange(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            throw new IllegalArgumentException("Visit Time is required.");
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            LocalTime time;
            if (DateUtil.isCellDateFormatted(cell)) {
                time = cell.getLocalDateTimeCellValue().toLocalTime();
            } else {
                int seconds = (int) Math.round(cell.getNumericCellValue() * 24 * 60 * 60);
                time = LocalTime.ofSecondOfDay(Math.floorMod(seconds, 24 * 60 * 60));
            }
            return new TimeRange(time, null);
        }

        String value = formatter.formatCellValue(cell).trim().toUpperCase(Locale.ENGLISH);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Visit Time is required.");
        }
        // Accept either a single time ("08:00") or a range ("08:00 - 08:30").
        String[] parts = value.split("\\s*-\\s*", 2);
        LocalTime startTime = parseTimeText(parts[0].trim(), value);
        LocalTime endTime = parts.length == 2 && !parts[1].trim().isBlank()
                ? parseTimeText(parts[1].trim(), value)
                : null;
        return new TimeRange(startTime, endTime);
    }

    private LocalTime parseTimeText(String value, String originalValue) {
        for (DateTimeFormatter timeFormatter : TIME_FORMATTERS) {
            try {
                return LocalTime.parse(value, timeFormatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        throw new IllegalArgumentException("Invalid visit time value: " + originalValue);
    }

    private String normalizeShift(String value, LocalTime startTime) {
        if (List.of("AM", "Lunch", "Tea", "Bed").contains(value)) {
            return value;
        }
        return shiftForTime(startTime);
    }

    private String shiftForTime(LocalTime time) {
        int minutes = time.getHour() * 60 + time.getMinute();
        if (minutes < 11 * 60) {
            return "AM";
        }
        if (minutes < 15 * 60 + 30) {
            return "Lunch";
        }
        if (minutes < 18 * 60 + 30) {
            return "Tea";
        }
        return "Bed";
    }

    private Mono<AnalysisResult> calculateMileage(List<VisitRow> visits) {
        List<VisitRow> orderedVisits = visits.stream()
                .sorted(Comparator.comparing(VisitRow::staffNumber)
                        .thenComparing(VisitRow::visitDate)
                        .thenComparing(VisitRow::startTime))
                .toList();

        Map<ShiftKey, List<VisitRow>> visitsByShift = new LinkedHashMap<>();
        for (VisitRow visit : orderedVisits) {
            visitsByShift.computeIfAbsent(new ShiftKey(visit.staffNumber(), visit.visitDate(), DAY_ROUTE), ignored -> new ArrayList<>()).add(visit);
        }

        // Resolve every distance the month needs up front, then optimise each shift in memory.
        return loadShiftDistances(visitsByShift.values())
                .publishOn(Schedulers.boundedElastic())
                .map(cache -> {
                    List<ShiftResult> shiftResults = new ArrayList<>();
                    for (Map.Entry<ShiftKey, List<VisitRow>> entry : visitsByShift.entrySet()) {
                        shiftResults.add(analyseShift(entry.getKey(), entry.getValue(), cache));
                    }
                    return toAnalysisResult(orderedVisits, shiftResults);
                });
    }

    private AnalysisResult toAnalysisResult(List<VisitRow> orderedVisits, List<ShiftResult> shifts) {
        Map<String, MileageResult> rowsByStaff = new TreeMap<>();
        for (ShiftResult shift : shifts) {
            MileageResult current = rowsByStaff.getOrDefault(shift.key().staffNumber(), new MileageResult(shift.key().staffNumber(), 0.0, 0.0, 0.0));
            rowsByStaff.put(shift.key().staffNumber(), new MileageResult(
                    shift.key().staffNumber(),
                    current.totalMileageKm() + shift.optimisedKm(),
                    current.totalOriginalMileageKm() + shift.originalKm(),
                    current.totalSavedMileageKm() + shift.savedKm()));
        }
        return new AnalysisResult(orderedVisits, new ArrayList<>(rowsByStaff.values()), shifts);
    }

    private ShiftResult analyseShift(ShiftKey key, List<VisitRow> visits, Map<String, Map<String, Double>> cache) {
        List<String> addresses = visits.stream().map(VisitRow::address).toList();
        if (addresses.size() < 2) {
            return new ShiftResult(key, visits, visits, 0.0, 0.0, 0.0, List.of(), List.of());
        }

        double[][] matrix = distanceMatrix(addresses, cache);
        List<Double> originalLegs = new ArrayList<>();
        for (int i = 0; i < addresses.size() - 1; i++) {
            originalLegs.add(matrix[i][i + 1]);
        }
        double originalKm = originalLegs.stream().mapToDouble(Double::doubleValue).sum();
        List<Integer> optIndexes = postcodeGroupedIndexes(addresses, matrix);
        List<VisitRow> optimisedVisits = optIndexes.stream().map(visits::get).toList();
        List<Double> optimisedLegs = new ArrayList<>();
        for (int i = 0; i < optIndexes.size() - 1; i++) {
            optimisedLegs.add(matrix[optIndexes.get(i)][optIndexes.get(i + 1)]);
        }
        double optimisedKm = optimisedLegs.stream().mapToDouble(Double::doubleValue).sum();
        return new ShiftResult(key, visits, optimisedVisits, originalKm, optimisedKm, originalKm - optimisedKm, originalLegs, optimisedLegs);
    }

    private double[][] distanceMatrix(List<String> addresses, Map<String, Map<String, Double>> cache) {
        double[][] distances = new double[addresses.size()][addresses.size()];
        copyCachedDistances(distances, addresses, cache);
        return distances;
    }

    private Mono<Map<String, Map<String, Double>>> loadShiftDistances(Collection<List<VisitRow>> shifts) {
        Map<String, String> addressByKey = new LinkedHashMap<>();
        List<List<String>> shiftKeys = new ArrayList<>();
        for (List<VisitRow> shift : shifts) {
            Set<String> keys = new LinkedHashSet<>();
            for (VisitRow visit : shift) {
                String key = normalizeCacheAddress(visit.address());
                if (StringUtils.hasText(key)) {
                    addressByKey.putIfAbsent(key, visit.address());
                    keys.add(key);
                }
            }
            if (keys.size() >= 2) {
                shiftKeys.add(new ArrayList<>(keys));
            }
        }

        return loadCachedDistances(addressByKey.keySet())
                .flatMap(cache -> fetchMissingDistances(planMissingDistanceBatches(shiftKeys, cache), addressByKey)
                        .doOnNext(entry -> cache.computeIfAbsent(entry.origin(), ignored -> new HashMap<>())
                                .put(entry.destination(), entry.distanceKm()))
                        .then(Mono.just(cache)));
    }

    private Mono<Map<String, Map<String, Double>>> loadCachedDistances(Set<String> addressKeys) {
        Map<String, Map<String, Double>> cache = new HashMap<>();
        return Flux.fromIterable(addressKeys)
                .buffer(CACHE_QUERY_CHUNK)
                .concatMap(origins -> databaseClient.sql("""
                                SELECT origin_address, destination_address, distance_km
                                FROM route_distance_cache
                                WHERE origin_address IN (:origins)
                                """)
                        .bind("origins", origins)
                        .map((row, metadata) -> new CachedDistance(
                                row.get("origin_address", String.class),
                                row.get("destination_address", String.class),
                                row.get("distance_km", Double.class)))
                        .all())
                .filter(entry -> entry.destination() != null && entry.distanceKm() != null && addressKeys.contains(entry.destination()))
                .doOnNext(entry -> cache.computeIfAbsent(entry.origin(), ignored -> new HashMap<>())
                        .put(entry.destination(), entry.distanceKm()))
                .then(Mono.just(cache));
    }

    // Only pairs inside a shift are needed. For each shift, pick a small set of addresses that touches every
    // missing pair and request just those rows and columns, so mostly-cached shifts cost a few elements, not n x n.
    private List<MatrixBatch> planMissingDistanceBatches(List<List<String>> shiftKeys, Map<String, Map<String, Double>> cache) {
        Set<String> planned = new HashSet<>();
        List<MatrixBatch> batches = new ArrayList<>();
        for (List<String> keys : shiftKeys) {
            Map<String, Set<String>> missingByOrigin = new HashMap<>();
            for (String origin : keys) {
                for (String destination : keys) {
                    if (!origin.equals(destination) && !hasCachedDistance(cache, origin, destination)
                            && !planned.contains(pairKey(origin, destination))) {
                        missingByOrigin.computeIfAbsent(origin, ignored -> new HashSet<>()).add(destination);
                    }
                }
            }
            if (missingByOrigin.isEmpty()) {
                continue;
            }

            List<String> cover = missingPairCover(keys, missingByOrigin);
            List<String> others = keys.stream().filter(key -> !cover.contains(key)).toList();
            addMatrixBatches(batches, planned, cover, keys);
            addMatrixBatches(batches, planned, others, cover);
        }
        return batches;
    }

    private List<String> missingPairCover(List<String> keys, Map<String, Set<String>> missingByOrigin) {
        List<String> cover = new ArrayList<>();
        while (!missingByOrigin.isEmpty()) {
            String best = null;
            int bestCount = 0;
            for (String key : keys) {
                int count = missingByOrigin.getOrDefault(key, Set.of()).size();
                for (Set<String> destinations : missingByOrigin.values()) {
                    if (destinations.contains(key)) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    best = key;
                    bestCount = count;
                }
            }
            String chosen = best;
            cover.add(chosen);
            missingByOrigin.remove(chosen);
            missingByOrigin.values().forEach(destinations -> destinations.remove(chosen));
            missingByOrigin.values().removeIf(Set::isEmpty);
        }
        return cover;
    }

    private void addMatrixBatches(List<MatrixBatch> batches, Set<String> planned, List<String> origins, List<String> destinations) {
        if (origins.isEmpty() || destinations.isEmpty()) {
            return;
        }
        for (int dStart = 0; dStart < destinations.size(); dStart += BATCH_MAX_DIM) {
            List<String> destinationChunk = destinations.subList(dStart, Math.min(destinations.size(), dStart + BATCH_MAX_DIM));
            int maxOrigins = Math.max(1, Math.min(BATCH_MAX_DIM, BATCH_MAX_ELEM / destinationChunk.size()));
            for (int oStart = 0; oStart < origins.size(); oStart += maxOrigins) {
                List<String> originChunk = origins.subList(oStart, Math.min(origins.size(), oStart + maxOrigins));
                batches.add(new MatrixBatch(List.copyOf(originChunk), List.copyOf(destinationChunk)));
                for (String origin : originChunk) {
                    for (String destination : destinationChunk) {
                        planned.add(pairKey(origin, destination));
                    }
                }
            }
        }
    }

    private String pairKey(String origin, String destination) {
        return origin + '\u0000' + destination;
    }

    private Flux<CachedDistance> fetchMissingDistances(List<MatrixBatch> batches, Map<String, String> addressByKey) {
        if (!batches.isEmpty()) {
            log.info("Fetching {} Google route matrix batches ({} elements)", batches.size(),
                    batches.stream().mapToInt(MatrixBatch::elements).sum());
        }
        // Fetch concurrently but write one batch at a time: concurrent upserts of overlapping pairs deadlock.
        return Flux.fromIterable(batches)
                .flatMap(batch -> Mono.delay(reserveRouteMatrixQuota(batch))
                        .then(fetchDistanceBatch(addressByKey, batch))
                        .flatMap(responses -> toCachedDistances(batch, responses)), ROUTE_MATRIX_CONCURRENCY)
                .concatMap(distances -> saveCachedDistances(distances).thenMany(Flux.fromIterable(distances)));
    }

    // Google bills and rate-limits the matrix per element (origins x destinations). Batches share one timeline
    // across concurrent uploads, so request latency overlaps the quota wait instead of adding to it.
    private Duration reserveRouteMatrixQuota(MatrixBatch batch) {
        long quotaNanos = Math.max(batch.elements() * 60_000_000_000L / routeMatrixElementsPerMinute, routeMatrixBatchDelay.toNanos());
        synchronized (routeMatrixQuotaLock) {
            long now = System.nanoTime();
            long start = Math.max(now, routeMatrixNextSlotNanos);
            routeMatrixNextSlotNanos = start + quotaNanos;
            return Duration.ofNanos(start - now);
        }
    }

    private boolean hasCachedDistance(Map<String, Map<String, Double>> cache, String origin, String destination) {
        return cache.getOrDefault(origin, Map.of()).containsKey(destination);
    }

    private Mono<List<GoogleDistanceMatrixResponse>> fetchDistanceBatch(Map<String, String> addressByKey, MatrixBatch batch) {
        return googleRoutesClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/distanceMatrix/v2:computeRouteMatrix")
                        .build())
                .header("X-Goog-Api-Key", requireGoogleApiKey())
                .header("X-Goog-FieldMask", "originIndex,destinationIndex,status,condition,distanceMeters")
                .bodyValue(buildGoogleRouteMatrixRequest(addressByKey, batch))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(googleRoutesMatrixException(response.statusCode().value(), body))))
                .bodyToFlux(GoogleDistanceMatrixResponse.class)
                .collectList()
                .retryWhen(Retry.backoff(5, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .filter(this::isRetryableGoogleRoutesMatrixError)
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure()));
    }

    private boolean isRetryableGoogleRoutesMatrixError(Throwable error) {
        if (error instanceof GoogleRoutesMatrixRateLimitException || error instanceof WebClientRequestException) {
            return true;
        }
        Throwable cause = error.getCause();
        while (cause != null) {
            String className = cause.getClass().getName();
            if (className.contains("SocketException") || className.contains("TimeoutException")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private RuntimeException googleRoutesMatrixException(int statusCode, String body) {
        String message = "Google Routes Matrix error (" + statusCode + "): " + body;
        if (statusCode == 429) {
            return new GoogleRoutesMatrixRateLimitException(message);
        }
        return new IllegalStateException(message);
    }

    private Map<String, Object> buildGoogleRouteMatrixRequest(Map<String, String> addressByKey, MatrixBatch batch) {
        return Map.of(
                "origins", batch.origins().stream()
                        .map(addressByKey::get)
                        .map(this::routeMatrixWaypoint)
                        .toList(),
                "destinations", batch.destinations().stream()
                        .map(addressByKey::get)
                        .map(this::routeMatrixWaypoint)
                        .toList(),
                "travelMode", "DRIVE",
                "routingPreference", "TRAFFIC_UNAWARE");
    }

    private Map<String, Object> routeMatrixWaypoint(String address) {
        return Map.of("waypoint", Map.of("address", address));
    }

    private Mono<List<CachedDistance>> toCachedDistances(MatrixBatch batch, List<GoogleDistanceMatrixResponse> responses) {
        if (responses == null) {
            return Mono.error(new IllegalStateException("Google Routes Matrix returned an empty response."));
        }

        List<CachedDistance> distances = responses.stream()
                .filter(response -> response != null
                        && response.getOriginIndex() != null
                        && response.getDestinationIndex() != null
                        && "ROUTE_EXISTS".equals(response.getCondition())
                        && response.getDistanceMeters() != null)
                .map(response -> new CachedDistance(
                        batch.origins().get(response.getOriginIndex()),
                        batch.destinations().get(response.getDestinationIndex()),
                        response.getDistanceMeters() / METERS_PER_KM))
                .filter(distance -> !distance.origin().equals(distance.destination()))
                // A consistent row order keeps concurrent uploads from deadlocking on the same pairs.
                .sorted(Comparator.comparing(CachedDistance::origin).thenComparing(CachedDistance::destination))
                .toList();

        return Mono.just(distances);
    }

    private Mono<Void> saveCachedDistances(List<CachedDistance> distances) {
        if (distances.isEmpty()) {
            return Mono.empty();
        }

        StringBuilder sql = new StringBuilder("INSERT INTO route_distance_cache (origin_address, destination_address, distance_km, updated_at) VALUES ");
        for (int i = 0; i < distances.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append("(:o").append(i).append(", :d").append(i).append(", :k").append(i).append(", NOW())");
        }
        sql.append("""
                 ON CONFLICT (origin_address, destination_address)
                DO UPDATE SET distance_km = EXCLUDED.distance_km, updated_at = NOW()
                """);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString());
        for (int i = 0; i < distances.size(); i++) {
            CachedDistance distance = distances.get(i);
            spec = spec.bind("o" + i, distance.origin())
                    .bind("d" + i, distance.destination())
                    .bind("k" + i, distance.distanceKm());
        }
        return spec.fetch().rowsUpdated().then();
    }

    private void copyCachedDistances(double[][] distances, List<String> addresses, Map<String, Map<String, Double>> cache) {
        for (int i = 0; i < addresses.size(); i++) {
            String origin = normalizeCacheAddress(addresses.get(i));
            for (int j = 0; j < addresses.size(); j++) {
                String destination = normalizeCacheAddress(addresses.get(j));
                if (i == j) {
                    distances[i][j] = 0.0;
                } else {
                    distances[i][j] = cache.getOrDefault(origin, Map.of()).getOrDefault(destination, 0.0);
                }
            }
        }
    }

    private String normalizeCacheAddress(String address) {
        return address == null ? "" : address.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ENGLISH);
    }

    private String requireGoogleApiKey() {
        if (!StringUtils.hasText(googleApiKey)) {
            throw new IllegalStateException("GOOGLE_API_KEY environment variable is required.");
        }
        return googleApiKey;
    }

    // Visits sharing a postcode stay together: order the postcode groups by the shortest drive between them,
    // then within each group start nearest to the previous visit and always drive to the closest next one.
    private List<Integer> postcodeGroupedIndexes(List<String> addresses, double[][] matrix) {
        Map<String, List<Integer>> visitsByPostcode = new LinkedHashMap<>();
        for (int i = 0; i < addresses.size(); i++) {
            String postcode = postcode(addresses.get(i));
            visitsByPostcode.computeIfAbsent(postcode == null ? "#" + i : postcode, ignored -> new ArrayList<>()).add(i);
        }
        List<List<Integer>> groups = new ArrayList<>(visitsByPostcode.values());
        if (groups.size() == addresses.size()) {
            return optimisedIndexes(matrix);
        }

        double[][] groupMatrix = new double[groups.size()][groups.size()];
        for (int a = 0; a < groups.size(); a++) {
            for (int b = 0; b < groups.size(); b++) {
                if (a == b) {
                    continue;
                }
                double closest = Double.MAX_VALUE;
                for (int from : groups.get(a)) {
                    for (int to : groups.get(b)) {
                        closest = Math.min(closest, matrix[from][to]);
                    }
                }
                groupMatrix[a][b] = closest;
            }
        }

        List<Integer> path = new ArrayList<>();
        for (int group : optimisedIndexes(groupMatrix)) {
            List<Integer> remaining = new ArrayList<>(groups.get(group));
            while (!remaining.isEmpty()) {
                Integer last = path.isEmpty() ? null : path.get(path.size() - 1);
                Integer next = last == null
                        ? remaining.get(0)
                        : remaining.stream().min(Comparator.comparingDouble(candidate -> matrix[last][candidate])).orElseThrow();
                path.add(next);
                remaining.remove(next);
            }
        }
        return path;
    }

    private String postcode(String address) {
        Matcher matcher = UK_POSTCODE.matcher(address == null ? "" : address.trim());
        if (!matcher.find()) {
            return null;
        }
        // A letter O typed for the inward zero (e.g. "BT49 OHS") is the same postcode.
        String inward = matcher.group(2).toUpperCase(Locale.ENGLISH);
        return matcher.group(1).toUpperCase(Locale.ENGLISH) + " " + (inward.charAt(0) == 'O' ? "0" + inward.substring(1) : inward);
    }

    private List<Integer> optimisedIndexes(double[][] matrix) {
        if (matrix.length <= EXACT_ROUTE_LIMIT) {
            return shortestPathExact(matrix);
        }
        return nearestNeighbourThenTwoOpt(matrix);
    }

    // Held-Karp: exact shortest open path starting at the day's first visit, O(n^2 * 2^n) instead of trying all n! orders.
    private List<Integer> shortestPathExact(double[][] matrix) {
        int n = matrix.length;
        int full = (1 << n) - 1;
        double[][] cost = new double[full + 1][n];
        int[][] previous = new int[full + 1][n];
        for (double[] row : cost) {
            Arrays.fill(row, Double.MAX_VALUE);
        }
        cost[1][0] = 0.0;
        previous[1][0] = -1;
        for (int mask = 1; mask <= full; mask++) {
            for (int last = 0; last < n; last++) {
                if ((mask & (1 << last)) == 0 || cost[mask][last] == Double.MAX_VALUE) {
                    continue;
                }
                for (int next = 0; next < n; next++) {
                    if ((mask & (1 << next)) != 0) {
                        continue;
                    }
                    int nextMask = mask | (1 << next);
                    double candidate = cost[mask][last] + matrix[last][next];
                    if (candidate < cost[nextMask][next]) {
                        cost[nextMask][next] = candidate;
                        previous[nextMask][next] = last;
                    }
                }
            }
        }

        int last = 0;
        for (int i = 1; i < n; i++) {
            if (cost[full][i] < cost[full][last]) {
                last = i;
            }
        }
        List<Integer> path = new ArrayList<>();
        for (int mask = full; last != -1; ) {
            path.add(last);
            int prior = previous[mask][last];
            mask &= ~(1 << last);
            last = prior;
        }
        Collections.reverse(path);
        return path;
    }

    // Start from the day's first visit, always go to the closest unvisited address, then let 2-opt remove crossings.
    private List<Integer> nearestNeighbourThenTwoOpt(double[][] matrix) {
        return twoOpt(nearestNeighbourPath(matrix, 0), matrix);
    }

    private List<Integer> nearestNeighbourPath(double[][] matrix, int start) {
        int n = matrix.length;
        boolean[] used = new boolean[n];
        used[start] = true;
        List<Integer> path = new ArrayList<>();
        path.add(start);
        while (path.size() < n) {
            int last = path.get(path.size() - 1);
            int next = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!used[i] && matrix[last][i] < best) {
                    best = matrix[last][i];
                    next = i;
                }
            }
            used[next] = true;
            path.add(next);
        }
        return path;
    }

    private List<Integer> twoOpt(List<Integer> path, double[][] matrix) {
        boolean improved = true;
        List<Integer> current = new ArrayList<>(path);
        while (improved) {
            improved = false;
            for (int i = 1; i < current.size() - 1; i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    List<Integer> candidate = new ArrayList<>(current.subList(0, i));
                    List<Integer> reversed = new ArrayList<>(current.subList(i, j + 1));
                    java.util.Collections.reverse(reversed);
                    candidate.addAll(reversed);
                    candidate.addAll(current.subList(j + 1, current.size()));
                    if (pathCost(candidate, matrix) < pathCost(current, matrix)) {
                        current = candidate;
                        improved = true;
                    }
                }
            }
        }
        return current;
    }

    private double pathCost(List<Integer> path, double[][] matrix) {
        double total = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            total += matrix[path.get(i)][path.get(i + 1)];
        }
        return total;
    }

    private Mono<List<MileageResult>> calculateMileageLegacy(List<VisitRow> visits) {
        Map<String, List<VisitRow>> visitsByStaff = new LinkedHashMap<>();
        for (VisitRow visit : visits) {
            visitsByStaff.computeIfAbsent(visit.staffNumber(), ignored -> new ArrayList<>()).add(visit);
        }

        return Flux.fromIterable(visitsByStaff.entrySet())
                .concatMap(entry -> calculateStaffMileage(entry.getKey(), entry.getValue()))
                .collectList();
    }

    private Map<YearMonth, List<VisitRow>> groupVisitsByMonth(List<VisitRow> visits) {
        Map<YearMonth, List<VisitRow>> visitsByMonth = new TreeMap<>();
        for (VisitRow visit : visits) {
            visitsByMonth.computeIfAbsent(YearMonth.from(visit.visitDateTime()), ignored -> new ArrayList<>()).add(visit);
        }
        return visitsByMonth;
    }

    private List<VisitRow> filterVisitsByReportMonth(List<VisitRow> visits, ReportMonthFilter filter) {
        if (filter == null) {
            return visits;
        }

        return visits.stream()
                .filter(visit -> visitMatchesFilter(YearMonth.from(visit.visitDateTime()), filter))
                .toList();
    }

    private boolean visitMatchesFilter(YearMonth visitMonth, ReportMonthFilter filter) {
        if (filter.exactReportMonth() != null) {
            return visitMonth.toString().equals(filter.exactReportMonth());
        }
        if (filter.year() != null && visitMonth.getYear() != filter.year()) {
            return false;
        }
        return filter.month() == null || visitMonth.getMonthValue() == filter.month();
    }

    private String reportMonth(YearMonth yearMonth) {
        return yearMonth.toString();
    }

    private ReportMonthFilter parseReportMonthFilter(String reportMonth, Integer year) {
        String monthText = reportMonth == null ? "" : reportMonth.trim();
        if (monthText.isBlank() && year == null) {
            return null;
        }

        if (monthText.matches("\\d{4}-\\d{2}")) {
            YearMonth parsed;
            try {
                parsed = YearMonth.parse(monthText);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid month value: " + reportMonth);
            }
            return new ReportMonthFilter(parsed.toString(), parsed.getYear(), parsed.getMonthValue());
        }

        if (year != null) {
            Integer month = monthText.isBlank() ? null : parseMonthValue(monthText)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid month value: " + reportMonth));
            String exact = month == null ? null : YearMonth.of(year, month).toString();
            return new ReportMonthFilter(exact, year, month);
        }

        Optional<Integer> monthOnly = parseMonthValue(monthText);
        if (monthOnly.isPresent()) {
            return new ReportMonthFilter(null, null, monthOnly.get());
        }

        ParsedMonthYear parsed = parseMonthYear(monthText)
                .orElseThrow(() -> new IllegalArgumentException("Use a month name, year, or both, for example: March 2026."));
        return new ReportMonthFilter(YearMonth.of(parsed.year(), parsed.month()).toString(), parsed.year(), parsed.month());
    }

    private Optional<ParsedMonthYear> parseMonthYear(String value) {
        String normalized = value.trim().replace(",", " ").replace("-", " ").replace("/", " ");
        String[] parts = normalized.split("\\s+");
        if (parts.length != 2) {
            return Optional.empty();
        }

        Integer parsedYear = null;
        Integer parsedMonth = null;
        for (String part : parts) {
            if (part.matches("\\d{4}")) {
                parsedYear = Integer.valueOf(part);
            } else {
                parsedMonth = parseMonthValue(part).orElse(null);
            }
        }

        if (parsedYear == null || parsedMonth == null) {
            return Optional.empty();
        }
        return Optional.of(new ParsedMonthYear(parsedMonth, parsedYear));
    }

    private Optional<Integer> parseMonthValue(String value) {
        String normalized = value.trim().toLowerCase(Locale.ENGLISH);
        if (normalized.matches("\\d{1,2}")) {
            int month = Integer.parseInt(normalized);
            return month >= 1 && month <= 12 ? Optional.of(month) : Optional.empty();
        }

        for (Month month : Month.values()) {
            String fullName = month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ENGLISH);
            String shortName = month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ENGLISH);
            if (normalized.equals(fullName) || normalized.equals(shortName)) {
                return Optional.of(month.getValue());
            }
        }
        return Optional.empty();
    }

    private boolean reportMonthMatches(String reportMonth, int month) {
        try {
            return YearMonth.parse(reportMonth).getMonthValue() == month;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private Mono<MileageResult> calculateStaffMileage(String staffNumber, List<VisitRow> visits) {
        visits.sort(Comparator.comparing(VisitRow::visitDateTime));

        Map<LocalDate, List<Location>> locationsByDay = new LinkedHashMap<>();
        for (VisitRow visit : visits) {
            if (visit.lat() != null && visit.lon() != null) {
                locationsByDay.computeIfAbsent(visit.visitDate(), ignored -> new ArrayList<>())
                        .add(new Location(visit.lat(), visit.lon()));
            }
        }

        return Flux.fromIterable(locationsByDay.values())
                .concatMap(locations -> calculateLocationDistances(staffNumber, locations))
                .reduce(new MileageResult(staffNumber, 0.0, 0.0, 0.0),
                        (total, day) -> new MileageResult(
                                staffNumber,
                                total.totalMileageKm() + day.totalMileageKm(),
                                total.totalOriginalMileageKm() + day.totalOriginalMileageKm(),
                                0.0));
    }

    private Mono<Location> geocode(VisitRow visit, Map<String, Mono<Location>> addressCache) {
        return addressCache.computeIfAbsent(visit.address(), address -> geocodingService.geocodeFirst(address)
                .map(response -> new Location(response.getLat(), response.getLon()))
                .cache());
    }

    private Mono<MileageResult> calculateLocationDistances(String staffNumber, List<Location> locations) {
        if (locations.size() < 2) {
            return Mono.just(new MileageResult(staffNumber, 0.0, 0.0, 0.0));
        }

        List<RoutePair> pairs = new ArrayList<>();
        for (int i = 1; i < locations.size(); i++) {
            pairs.add(new RoutePair(locations.get(i - 1), locations.get(i)));
        }
        double totalStraightLineMileageKm = pairs.stream()
                .mapToDouble(pair -> straightLineDistanceKm(pair.origin(), pair.destination()))
                .sum();

        return Flux.fromIterable(pairs)
                .concatMap(pair -> routingService.calculateDistanceKm(
                        pair.origin().lat(),
                        pair.origin().lon(),
                        pair.destination().lat(),
                        pair.destination().lon()))
                .reduce(0.0, Double::sum)
                .map(total -> new MileageResult(staffNumber, total, totalStraightLineMileageKm, 0.0));
    }

    private Map<String, Double> straightLineMileageByStaff(List<WorkerMileageVisit> visits) {
        Map<String, List<WorkerMileageVisit>> visitsByStaff = new LinkedHashMap<>();
        for (WorkerMileageVisit visit : visits) {
            visitsByStaff.computeIfAbsent(visit.getStaffNumber(), ignored -> new ArrayList<>()).add(visit);
        }

        Map<String, Double> totals = new HashMap<>();
        for (Map.Entry<String, List<WorkerMileageVisit>> entry : visitsByStaff.entrySet()) {
            Map<LocalDate, List<Location>> locationsByDay = new LinkedHashMap<>();
            entry.getValue().stream()
                    .sorted(Comparator.comparing(WorkerMileageVisit::getVisitDate)
                            .thenComparing(visit -> visit.getId() == null ? 0L : visit.getId()))
                    .filter(visit -> visit.getLat() != null && visit.getLon() != null)
                    .forEach(visit -> locationsByDay.computeIfAbsent(visit.getVisitDate(), ignored -> new ArrayList<>())
                            .add(new Location(visit.getLat(), visit.getLon())));

            double total = locationsByDay.values().stream()
                    .mapToDouble(this::totalStraightLineMileageKm)
                    .sum();
            totals.put(entry.getKey(), total);
        }
        return totals;
    }

    private double totalStraightLineMileageKm(List<Location> locations) {
        double total = 0.0;
        for (int i = 1; i < locations.size(); i++) {
            total += straightLineDistanceKm(locations.get(i - 1), locations.get(i));
        }
        return total;
    }

    private double straightLineDistanceKm(Location origin, Location destination) {
        final double earthRadiusKm = 6371.0088;
        double latDistance = Math.toRadians(destination.lat() - origin.lat());
        double lonDistance = Math.toRadians(destination.lon() - origin.lon());
        double originLatRadians = Math.toRadians(origin.lat());
        double destinationLatRadians = Math.toRadians(destination.lat());

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(originLatRadians) * Math.cos(destinationLatRadians)
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private double roundMileage(double mileage) {
        return Math.round(mileage * 100.0) / 100.0;
    }

    private double kmToMiles(double kilometers) {
        return roundMileage(kilometers * KM_TO_MILES);
    }

    private List<WorkerMileageTotalResponse> toUploadedMileageTotalResponses(List<WorkerMileageReportResponse> reports) {
        if (reports.isEmpty()) {
            return List.of();
        }

        Map<String, List<WorkerMileageReportResponse>> reportsByMonth = new TreeMap<>();
        for (WorkerMileageReportResponse report : reports) {
            reportsByMonth.computeIfAbsent(report.getReportMonth(), ignored -> new ArrayList<>()).add(report);
        }

        List<WorkerMileageTotalResponse> totals = new ArrayList<>();
        totals.add(toUploadedMileageTotalResponse("all", "Total mileage for all uploaded months", reports));
        for (Map.Entry<String, List<WorkerMileageReportResponse>> entry : reportsByMonth.entrySet()) {
            totals.add(toUploadedMileageTotalResponse(entry.getKey(), monthLabel(entry.getKey()), entry.getValue()));
        }
        return totals;
    }

    private WorkerMileageTotalResponse toUploadedMileageTotalResponse(String reportMonth, String label, List<WorkerMileageReportResponse> reports) {
        Map<String, StaffMileageTotals> totalsByStaff = new TreeMap<>();
        double totalMileageKm = 0.0;
        double totalStraightLineMileageKm = 0.0;

        for (WorkerMileageReportResponse report : reports) {
            for (WorkerMileageReportRowResponse row : report.getRows()) {
                double rowMileageKm = rowMileageKm(row);
                double rowStraightLineMileageKm = rowStraightLineMileageKm(row);
                totalMileageKm += rowMileageKm;
                totalStraightLineMileageKm += rowStraightLineMileageKm;
                totalsByStaff.computeIfAbsent(row.getStaffNumber(), ignored -> new StaffMileageTotals())
                        .add(rowMileageKm, rowStraightLineMileageKm);
            }
        }

        List<WorkerMileageTotalRowResponse> rows = totalsByStaff.entrySet().stream()
                .map(entry -> WorkerMileageTotalRowResponse.builder()
                        .staffNumber(entry.getKey())
                        .totalMileageMiles(kmToMiles(entry.getValue().totalMileage()))
                        .totalStraightLineMileage(roundMileage(entry.getValue().totalStraightLineMileage()))
                        .totalStraightLineMileageKm(roundMileage(entry.getValue().totalStraightLineMileage()))
                        .totalStraightLineMileageMiles(kmToMiles(entry.getValue().totalStraightLineMileage()))
                        .build())
                .toList();

        return WorkerMileageTotalResponse.builder()
                .reportMonth(reportMonth)
                .uploadId(uploadIdFromReportResponses(reports))
                .label(label)
                .totalMileageMiles(kmToMiles(totalMileageKm))
                .totalStraightLineMileageMiles(kmToMiles(totalStraightLineMileageKm))
                .reportCount(reports.size())
                .rows(rows)
                .build();
    }

    private double rowMileageKm(WorkerMileageReportRowResponse row) {
        if (row.getTotalMileageKm() != null) {
            return row.getTotalMileageKm();
        }
        return row.getTotalMileage() == null ? 0.0 : row.getTotalMileage();
    }

    private double rowStraightLineMileageKm(WorkerMileageReportRowResponse row) {
        if (row.getTotalStraightLineMileageKm() != null) {
            return row.getTotalStraightLineMileageKm();
        }
        return row.getTotalStraightLineMileage() == null ? 0.0 : row.getTotalStraightLineMileage();
    }

    private Long uploadIdFromReportResponses(List<WorkerMileageReportResponse> reports) {
        return reports.stream()
                .map(WorkerMileageReportResponse::getUploadId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
    }

    private WorkerMileageUploadResponse toUploadResponse(WorkerMileageUpload upload,
                                                        List<WorkerMileageReportResponse> reports,
                                                        boolean includeReports) {
        List<String> reportMonths = reports.stream()
                .map(WorkerMileageReportResponse::getReportMonth)
                .distinct()
                .sorted()
                .toList();

        return WorkerMileageUploadResponse.builder()
                .id(upload.getId())
                .fileName(upload.getFileName())
                .createdAt(upload.getCreatedAt())
                .status(upload.getStatus() == null ? UPLOAD_COMPLETED : upload.getStatus())
                .errorMessage(upload.getErrorMessage())
                .reportCount(reports.size())
                .reportMonths(reportMonths)
                .totals(toUploadedMileageTotalResponses(reports))
                .reports(includeReports ? reports : List.of())
                .build();
    }

    private Mono<Void> cleanupFailedUpload(Long uploadId) {
        if (uploadId == null) {
            return Mono.empty();
        }
        return reportRepository.deleteByUploadId(uploadId)
                .then(uploadRepository.deleteById(uploadId));
    }

    private WorkerMileageTotalResponse toStoredMileageTotalResponse(String reportMonth, String label, List<ReportRows> reports) {
        Map<String, StaffMileageTotals> totalsByStaff = new TreeMap<>();
        double totalMileage = 0.0;
        double totalStraightLineMileage = 0.0;

        for (ReportRows report : reports) {
            Map<String, Double> straightLineMileageByStaff = straightLineMileageByStaff(report.visits());
            for (WorkerMileageReportRow row : report.rows()) {
                double rowMileage = row.getTotalMileage() == null ? 0.0 : row.getTotalMileage();
                double storedStraightLineMileage = row.getTotalStraightLineMileage() == null ? 0.0 : row.getTotalStraightLineMileage();
                double calculatedStraightLineMileage = straightLineMileageByStaff.getOrDefault(row.getStaffNumber(), 0.0);
                double rowStraightLineMileage = calculatedStraightLineMileage > 0.0 ? calculatedStraightLineMileage : storedStraightLineMileage;
                totalMileage += rowMileage;
                totalStraightLineMileage += rowStraightLineMileage;

                totalsByStaff.computeIfAbsent(row.getStaffNumber(), ignored -> new StaffMileageTotals())
                        .add(rowMileage, rowStraightLineMileage);
            }
        }

        List<WorkerMileageTotalRowResponse> rows = totalsByStaff.entrySet().stream()
                .map(entry -> WorkerMileageTotalRowResponse.builder()
                        .staffNumber(entry.getKey())
                        .totalMileageMiles(kmToMiles(entry.getValue().totalMileage()))
                        .totalStraightLineMileage(roundMileage(entry.getValue().totalStraightLineMileage()))
                        .totalStraightLineMileageKm(roundMileage(entry.getValue().totalStraightLineMileage()))
                        .totalStraightLineMileageMiles(kmToMiles(entry.getValue().totalStraightLineMileage()))
                        .build())
                .toList();

        return WorkerMileageTotalResponse.builder()
                .reportMonth(reportMonth)
                .uploadId(uploadIdFromReportRows(reports))
                .label(label)
                .totalMileageMiles(kmToMiles(totalMileage))
                .totalStraightLineMileageMiles(kmToMiles(totalStraightLineMileage))
                .reportCount(reports.size())
                .rows(rows)
                .build();
    }

    private String monthLabel(String reportMonth) {
        try {
            YearMonth yearMonth = YearMonth.parse(reportMonth);
            return yearMonth.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
                    + " " + yearMonth.getYear();
        } catch (DateTimeParseException e) {
            return reportMonth;
        }
    }

    private List<ReportRows> latestReportRowsByMonth(List<ReportRows> reports) {
        Map<String, ReportRows> latestByMonth = new TreeMap<>();
        for (ReportRows rows : reports) {
            String reportMonth = rows.report().getReportMonth();
            ReportRows current = latestByMonth.get(reportMonth);
            if (current == null || isNewerReport(rows.report(), current.report())) {
                latestByMonth.put(reportMonth, rows);
            }
        }
        return new ArrayList<>(latestByMonth.values());
    }

    private List<ReportVisits> latestReportVisitsByMonth(List<ReportVisits> reports) {
        Map<String, ReportVisits> latestByMonth = new TreeMap<>();
        for (ReportVisits visits : reports) {
            String reportMonth = visits.report().getReportMonth();
            ReportVisits current = latestByMonth.get(reportMonth);
            if (current == null || isNewerReport(visits.report(), current.report())) {
                latestByMonth.put(reportMonth, visits);
            }
        }
        return new ArrayList<>(latestByMonth.values());
    }

    private boolean isNewerReport(WorkerMileageReport candidate, WorkerMileageReport current) {
        LocalDateTime candidateCreatedAt = candidate.getCreatedAt();
        LocalDateTime currentCreatedAt = current.getCreatedAt();
        if (candidateCreatedAt != null && currentCreatedAt != null) {
            int comparison = candidateCreatedAt.compareTo(currentCreatedAt);
            if (comparison != 0) {
                return comparison > 0;
            }
        } else if (candidateCreatedAt != null) {
            return true;
        } else if (currentCreatedAt != null) {
            return false;
        }

        long candidateId = candidate.getId() == null ? Long.MIN_VALUE : candidate.getId();
        long currentId = current.getId() == null ? Long.MIN_VALUE : current.getId();
        return candidateId > currentId;
    }

    private byte[] buildWorkbook(AnalysisResult analysis) throws IOException {
        try (Workbook workbook = newReportWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            writeOptimisedMileageSheet(workbook, analysis.rows());
            writeOptimisedScheduleSheet(workbook, analysis);
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private void writeOptimisedMileageSheet(Workbook workbook, List<MileageResult> results) {
        Sheet sheet = createSheet(workbook, "Mileage Results");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Staff Number");
        header.createCell(1).setCellValue("Optimized Mileage (miles)");

        for (int i = 0; i < results.size(); i++) {
            MileageResult result = results.get(i);
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(result.staffNumber());
            row.createCell(1).setCellValue(kmToMiles(result.totalMileageKm()));
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    private void writeOptimisedScheduleSheet(Workbook workbook, AnalysisResult analysis) {
        Sheet sheet = createSheet(workbook, "Optimised Schedule");
        Row header = sheet.createRow(0);
        List<String> headers = List.of("Carer_No", "Client Address", "Name", "Date_of_Call", "Time_of_Call", "Shifts");
        for (int i = 0; i < headers.size(); i++) {
            header.createCell(i).setCellValue(headers.get(i));
        }

        List<VisitRow> rows = analysis.shifts().stream()
                .sorted(Comparator.comparing((ShiftResult shift) -> shift.key().staffNumber())
                        .thenComparing(shift -> shift.key().date())
                        .thenComparingInt(shift -> shiftSort(shift.key().shift())))
                .flatMap(shift -> shift.optimisedVisits().stream())
                .toList();
        for (int i = 0; i < rows.size(); i++) {
            VisitRow visit = rows.get(i);
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(visit.staffNumber());
            row.createCell(1).setCellValue(visit.address());
            row.createCell(2).setCellValue(visit.name());
            row.createCell(3).setCellValue(visit.visitDate().toString());
            row.createCell(4).setCellValue(formatTimeRange(visit));
            row.createCell(5).setCellValue(visit.shift());
        }
        autosize(sheet, headers.size());
    }

    private List<VisitRow> optimisedVisits(AnalysisResult analysis) {
        return analysis.shifts().stream()
                .sorted(Comparator.comparing((ShiftResult shift) -> shift.key().staffNumber())
                        .thenComparing(shift -> shift.key().date())
                        .thenComparingInt(shift -> shiftSort(shift.key().shift())))
                .flatMap(shift -> shift.optimisedVisits().stream())
                .toList();
    }

    private void writeCarerTotalsSheet(Workbook workbook, List<MileageResult> results) {
        Sheet sheet = createSheet(workbook, "Carer Totals");
        Row header = sheet.createRow(0);
        List<String> headers = List.of("Carer_No", "Total_Original_miles", "Total_Optimised_miles", "Total_Saved_miles", "Saved_pct");
        for (int i = 0; i < headers.size(); i++) {
            header.createCell(i).setCellValue(headers.get(i));
        }

        for (int i = 0; i < results.size(); i++) {
            MileageResult result = results.get(i);
            Row row = sheet.createRow(i + 1);
            double originalMiles = kmToMiles(result.totalOriginalMileageKm());
            double optimisedMiles = kmToMiles(result.totalMileageKm());
            double savedMiles = kmToMiles(result.totalSavedMileageKm());
            row.createCell(0).setCellValue(result.staffNumber());
            row.createCell(1).setCellValue(originalMiles);
            row.createCell(2).setCellValue(optimisedMiles);
            row.createCell(3).setCellValue(savedMiles);
            row.createCell(4).setCellValue(originalMiles == 0.0 ? 0.0 : roundMileage((savedMiles * 100.0) / originalMiles));
        }
        autosize(sheet, headers.size());
    }

    private void writeDailyTotalsSheet(Workbook workbook, List<ShiftResult> shifts) {
        Sheet sheet = createSheet(workbook, "Daily Totals");
        Row header = sheet.createRow(0);
        List<String> headers = List.of("Carer_No", "Date", "Total_Original_miles", "Total_Optimised_miles", "Total_Saved_miles", "Saved_pct", "Sessions");
        for (int i = 0; i < headers.size(); i++) {
            header.createCell(i).setCellValue(headers.get(i));
        }

        List<DailyMileageResult> results = dailyMileageResults(shifts);
        for (int i = 0; i < results.size(); i++) {
            DailyMileageResult result = results.get(i);
            double originalMiles = kmToMiles(result.originalKm());
            double optimisedMiles = kmToMiles(result.optimisedKm());
            double savedMiles = kmToMiles(result.savedKm());
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(result.staffNumber());
            row.createCell(1).setCellValue(result.date().toString());
            row.createCell(2).setCellValue(originalMiles);
            row.createCell(3).setCellValue(optimisedMiles);
            row.createCell(4).setCellValue(savedMiles);
            row.createCell(5).setCellValue(originalMiles == 0.0 ? 0.0 : Math.round((savedMiles * 1000.0) / originalMiles) / 10.0);
            row.createCell(6).setCellValue(String.join(", ", result.sessions()));
        }
        autosize(sheet, headers.size());
    }

    private List<DailyMileageResult> dailyMileageResults(List<ShiftResult> shifts) {
        Map<DailyKey, DailyMileageAccumulator> totalsByDay = new LinkedHashMap<>();
        shifts.stream()
                .sorted(Comparator.comparing((ShiftResult shift) -> shift.key().staffNumber())
                        .thenComparing(shift -> shift.key().date())
                        .thenComparingInt(shift -> shiftSort(shift.key().shift())))
                .forEach(shift -> totalsByDay.computeIfAbsent(
                                new DailyKey(shift.key().staffNumber(), shift.key().date()),
                                ignored -> new DailyMileageAccumulator())
                        .add(shift));

        List<DailyMileageResult> results = new ArrayList<>();
        for (Map.Entry<DailyKey, DailyMileageAccumulator> entry : totalsByDay.entrySet()) {
            DailyMileageAccumulator totals = entry.getValue();
            results.add(new DailyMileageResult(
                    entry.getKey().staffNumber(),
                    entry.getKey().date(),
                    totals.originalKm(),
                    totals.optimisedKm(),
                    totals.savedKm(),
                    totals.sessions()));
        }
        return results;
    }

    private void writeShiftBreakdownSheet(Workbook workbook, List<ShiftResult> shifts) {
        Sheet sheet = createSheet(workbook, "Shift Breakdown");
        Row header = sheet.createRow(0);
        List<String> headers = List.of("Carer_No", "Date", "Shift", "Visits", "Original_miles", "Optimised_miles", "Saved_miles", "Saved_pct", "Original_order", "Optimised_order");
        for (int i = 0; i < headers.size(); i++) {
            header.createCell(i).setCellValue(headers.get(i));
        }

        for (int i = 0; i < shifts.size(); i++) {
            ShiftResult shift = shifts.get(i);
            double originalMiles = kmToMiles(shift.originalKm());
            double optimisedMiles = kmToMiles(shift.optimisedKm());
            double savedMiles = kmToMiles(shift.savedKm());
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(shift.key().staffNumber());
            row.createCell(1).setCellValue(shift.key().date().toString());
            row.createCell(2).setCellValue(shift.key().shift());
            row.createCell(3).setCellValue(shift.originalVisits().size());
            row.createCell(4).setCellValue(originalMiles);
            row.createCell(5).setCellValue(optimisedMiles);
            row.createCell(6).setCellValue(savedMiles);
            row.createCell(7).setCellValue(originalMiles == 0.0 ? 0.0 : Math.round((savedMiles * 1000.0) / originalMiles) / 10.0);
            row.createCell(8).setCellValue(orderText(shift.originalVisits()));
            row.createCell(9).setCellValue(orderText(shift.optimisedVisits()));
        }
        autosize(sheet, headers.size());
    }

    private void writeLegsSheet(Workbook workbook, List<ShiftResult> shifts) {
        Sheet sheet = createSheet(workbook, "Legs");
        Row header = sheet.createRow(0);
        List<String> headers = List.of("Carer_No", "Date", "Shift", "Type", "Step", "From", "To", "Distance_miles");
        for (int i = 0; i < headers.size(); i++) {
            header.createCell(i).setCellValue(headers.get(i));
        }

        int rowIndex = 1;
        for (ShiftResult shift : shifts) {
            rowIndex = writeLegRows(sheet, rowIndex, shift, "Original", shift.originalVisits(), shift.originalLegsKm());
            rowIndex = writeLegRows(sheet, rowIndex, shift, "Optimised", shift.optimisedVisits(), shift.optimisedLegsKm());
        }
        autosize(sheet, headers.size());
    }

    private int writeLegRows(Sheet sheet, int rowIndex, ShiftResult shift, String type, List<VisitRow> visits, List<Double> legsKm) {
        for (int i = 0; i < legsKm.size(); i++) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(shift.key().staffNumber());
            row.createCell(1).setCellValue(shift.key().date().toString());
            row.createCell(2).setCellValue(shift.key().shift());
            row.createCell(3).setCellValue(type);
            row.createCell(4).setCellValue(i + 1);
            row.createCell(5).setCellValue(visits.get(i).address());
            row.createCell(6).setCellValue(visits.get(i + 1).address());
            row.createCell(7).setCellValue(kmToMiles(legsKm.get(i)));
        }
        return rowIndex;
    }

    private String orderText(List<VisitRow> visits) {
        boolean hasNames = visits.stream().anyMatch(visit -> StringUtils.hasText(visit.name()));
        return String.join(" -> ", visits.stream()
                .map(visit -> hasNames && StringUtils.hasText(visit.name()) ? visit.name() : visit.address())
                .toList());
    }

    private String formatTimeRange(VisitRow visit) {
        return formatTimeRange(visit.startTime(), visit.endTime());
    }

    private String formatTimeRange(LocalTime startTime, LocalTime endTime) {
        String start = startTime == null ? "" : startTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        String end = endTime == null ? "" : endTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        return end.isBlank() ? start : start + "-" + end;
    }

    private int shiftSort(String shift) {
        return switch (shift) {
            case "AM" -> 0;
            case "Lunch" -> 1;
            case "Tea" -> 2;
            case "Bed" -> 3;
            default -> 99;
        };
    }

    // Streams rows to compressed temp files so large reports don't hold every row in memory.
    private Workbook newReportWorkbook() {
        return new SXSSFWorkbook(null, REPORT_ROW_WINDOW, true) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    dispose();
                }
            }
        };
    }

    private Sheet createSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.createSheet(name);
        // Streamed rows are flushed as they are written, so column widths must be tracked up front.
        if (sheet instanceof SXSSFSheet streamingSheet) {
            streamingSheet.trackAllColumnsForAutoSizing();
        }
        return sheet;
    }

    private void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private byte[] buildWorkbookFromRows(List<WorkerMileageReportRow> rows) throws IOException {
        try (Workbook workbook = newReportWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = createSheet(workbook, "Mileage Results");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Staff Number");
            header.createCell(1).setCellValue("Driving Mileage (miles)");

            for (int i = 0; i < rows.size(); i++) {
                WorkerMileageReportRow result = rows.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(result.getStaffNumber());
                row.createCell(1).setCellValue(kmToMiles(result.getTotalMileage() == null ? 0.0 : result.getTotalMileage()));
            }

            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] buildMasterMileageWorkbook(List<ReportRows> reports) throws IOException {
        Map<String, StaffMileageTotals> totalsByStaff = masterMileageTotalsByStaff(reports);

        try (Workbook workbook = newReportWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = createSheet(workbook, "Mileage Summary");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Staff Number");
            header.createCell(1).setCellValue("Original Mileage");
            header.createCell(2).setCellValue("Optimized Mileage");
            header.createCell(3).setCellValue("Variance (Saved)");

            int rowIndex = 1;
            for (Map.Entry<String, StaffMileageTotals> entry : totalsByStaff.entrySet()) {
                double originalMiles = kmToMiles(entry.getValue().totalStraightLineMileage());
                double optimizedMiles = kmToMiles(entry.getValue().totalMileage());
                double savedMiles = roundMileage(Math.max(0.0, originalMiles - optimizedMiles));

                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(entry.getKey());
                row.createCell(1).setCellValue(originalMiles);
                row.createCell(2).setCellValue(optimizedMiles);
                row.createCell(3).setCellValue(savedMiles);
            }

            autosize(sheet, 4);
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private Map<String, StaffMileageTotals> masterMileageTotalsByStaff(List<ReportRows> reports) {
        Map<String, StaffMileageTotals> totalsByStaff = new TreeMap<>();
        for (ReportRows report : reports) {
            Map<String, Double> straightLineMileageByStaff = straightLineMileageByStaff(report.visits());
            for (WorkerMileageReportRow row : report.rows()) {
                double rowMileage = row.getTotalMileage() == null ? 0.0 : row.getTotalMileage();
                double storedStraightLineMileage = row.getTotalStraightLineMileage() == null ? 0.0 : row.getTotalStraightLineMileage();
                double calculatedStraightLineMileage = straightLineMileageByStaff.getOrDefault(row.getStaffNumber(), 0.0);
                double rowStraightLineMileage = calculatedStraightLineMileage > 0.0 ? calculatedStraightLineMileage : storedStraightLineMileage;

                totalsByStaff.computeIfAbsent(row.getStaffNumber(), ignored -> new StaffMileageTotals())
                        .add(rowMileage, rowStraightLineMileage);
            }
        }
        return totalsByStaff;
    }

    private byte[] buildOptimisedRoutesWorkbook(List<ReportVisits> reports) throws IOException {
        try (Workbook workbook = newReportWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = createSheet(workbook, "Optimized Routes");
            Row header = sheet.createRow(0);
            List<String> headers = List.of(
                    "Staff Number",
                    "Date",
                    "Visit Time",
                    "Optimized Routes");
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }

            List<WorkerMileageVisit> visits = reports.stream()
                    .sorted(Comparator.comparing((ReportVisits report) -> report.report().getReportMonth()))
                    .flatMap(report -> report.visits().stream())
                    .toList();
            List<WorkerMileageVisitResponse> visitResponses = toVisitResponsesWithMileage(visits, true)
                    .collectList()
                    .blockOptional()
                    .orElse(List.of());

            int rowIndex = 1;
            for (WorkerMileageVisitResponse visit : visitResponses) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(visit.getStaffNumber());
                row.createCell(1).setCellValue(visit.getVisitDate() == null ? "" : visit.getVisitDate().toString());
                row.createCell(2).setCellValue(formatTimeRange(visit.getStartTime(), visit.getEndTime()));
                row.createCell(3).setCellValue(visit.getAddress());
            }

            autosize(sheet, headers.size());
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private Long uploadIdFromReportRows(List<ReportRows> reports) {
        return reports.stream()
                .map(reportRows -> reportRows.report().getUploadId())
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
    }

    private Mono<WorkerMileageReportResponse> saveReport(Long uploadId, String reportMonth, List<VisitRow> visits,
                                                         List<MileageResult> results, byte[] workbook, boolean includeVisits) {
        LocalDateTime createdAt = LocalDateTime.now();
        WorkerMileageReport report = WorkerMileageReport.builder()
                .uploadId(uploadId)
                .reportMonth(reportMonth)
                .fileName(reportFileName(reportMonth, createdAt))
                .fileContent(workbook)
                .createdAt(createdAt)
                .build();

        return reportRepository.save(report)
                .flatMap(saved -> saveReportRows(saved.getId(), results)
                        .then(saveReportVisits(saved.getId(), visits))
                        .then(includeVisits
                                ? toReportResponse(saved)
                                : Mono.just(toReportResponse(saved, toReportRowResponses(results), List.of()))));
    }

    private String reportFileName(String reportMonth, LocalDateTime createdAt) {
        String monthCode = reportMonth == null ? "" : reportMonth.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        String monthName = monthLabel(reportMonth)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        String month = monthCode.isBlank() || monthCode.equals(monthName)
                ? monthName
                : monthCode + "-" + monthName;
        String generatedDate = createdAt.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return "worker-mileage-" + month + "-generated-" + generatedDate + ".xlsx";
    }

    private Mono<Void> saveReportRows(Long reportId, List<MileageResult> results) {
        return Flux.fromIterable(results)
                .map(result -> WorkerMileageReportRow.builder()
                        .reportId(reportId)
                        .staffNumber(result.staffNumber())
                        .totalMileage(roundMileage(result.totalMileageKm()))
                        .totalStraightLineMileage(roundMileage(result.totalOriginalMileageKm()))
                        .totalSavedMileage(roundMileage(result.totalSavedMileageKm()))
                        .build())
                .flatMap(reportRowRepository::save)
                .then();
    }

    private Mono<Void> saveReportVisits(Long reportId, List<VisitRow> visits) {
        LocalDateTime now = LocalDateTime.now();
        return Flux.fromIterable(visits)
                .map(visit -> WorkerMileageVisit.builder()
                        .reportId(reportId)
                        .staffNumber(visit.staffNumber())
                        .visitDate(visit.visitDate())
                        .startTime(visit.startTime())
                        .endTime(visit.endTime())
                        .address(visit.address())
                        .lat(visit.lat())
                        .lon(visit.lon())
                        .createdAt(now)
                        .build())
                // Saved one at a time so ids follow the optimised route order.
                .concatMap(visitRepository::save)
                .then();
    }

    private Mono<WorkerMileageReportResponse> toReportResponse(WorkerMileageReport report) {
        return toReportResponse(report, true);
    }

    private Mono<WorkerMileageReportResponse> toReportResponse(WorkerMileageReport report, boolean includeVisits) {
        Mono<List<WorkerMileageReportRow>> rows = reportRowRepository.findByReportIdOrderByStaffNumberAsc(report.getId())
                .collectList();

        if (!includeVisits) {
            return rows.map(this::toReportRowResponses)
                    .map(rowResponses -> toReportResponse(report, rowResponses, List.of()));
        }

        Mono<List<WorkerMileageVisit>> visits = visitRepository.findByReportIdOrderByStaffNumberAscVisitDateAscIdAsc(report.getId())
                .collectList();

        return Mono.zip(rows, visits)
                .flatMap(tuple -> toVisitResponsesWithMileage(tuple.getT2(), true)
                        .collectList()
                        .map(visitResponses -> toReportResponse(report, toReportRowResponses(tuple.getT1()), visitResponses)));
    }

    private List<WorkerMileageReportRowResponse> toReportRowResponses(List<?> rows) {
        return rows.stream()
                .map(row -> {
                    if (row instanceof WorkerMileageReportRow reportRow) {
                        double totalMileage = reportRow.getTotalMileage() == null ? 0.0 : reportRow.getTotalMileage();
                        double straightLineMileage = reportRow.getTotalStraightLineMileage() == null ? 0.0 : reportRow.getTotalStraightLineMileage();
                        return WorkerMileageReportRowResponse.builder()
                                .staffNumber(reportRow.getStaffNumber())
                                .totalMileage(reportRow.getTotalMileage())
                                .totalMileageKm(reportRow.getTotalMileage())
                                .totalMileageMiles(kmToMiles(totalMileage))
                                .totalStraightLineMileage(reportRow.getTotalStraightLineMileage())
                                .totalStraightLineMileageKm(reportRow.getTotalStraightLineMileage())
                                .totalStraightLineMileageMiles(kmToMiles(straightLineMileage))
                                .totalSavedMileage(reportRow.getTotalSavedMileage())
                                .totalSavedMileageKm(reportRow.getTotalSavedMileage())
                                .totalSavedMileageMiles(kmToMiles(reportRow.getTotalSavedMileage() == null ? 0.0 : reportRow.getTotalSavedMileage()))
                                .build();
                    }

                    MileageResult result = (MileageResult) row;
                    return WorkerMileageReportRowResponse.builder()
                            .staffNumber(result.staffNumber())
                            .totalMileage(roundMileage(result.totalMileageKm()))
                            .totalMileageKm(roundMileage(result.totalMileageKm()))
                            .totalMileageMiles(kmToMiles(result.totalMileageKm()))
                            .totalStraightLineMileage(roundMileage(result.totalOriginalMileageKm()))
                            .totalStraightLineMileageKm(roundMileage(result.totalOriginalMileageKm()))
                            .totalStraightLineMileageMiles(kmToMiles(result.totalOriginalMileageKm()))
                            .totalSavedMileage(roundMileage(result.totalSavedMileageKm()))
                            .totalSavedMileageKm(roundMileage(result.totalSavedMileageKm()))
                            .totalSavedMileageMiles(kmToMiles(result.totalSavedMileageKm()))
                            .build();
                })
                .toList();
    }

    private WorkerMileageReportResponse toReportResponse(WorkerMileageReport report,
                                                         List<WorkerMileageReportRowResponse> rows,
                                                         List<WorkerMileageVisitResponse> visits) {
        return WorkerMileageReportResponse.builder()
                .id(report.getId())
                .uploadId(report.getUploadId())
                .reportMonth(report.getReportMonth())
                .fileName(report.getFileName())
                .downloadUrl("/api/mileage/reports/" + report.getId() + "/download")
                .createdAt(report.getCreatedAt())
                .rows(rows)
                .visits(visits)
                .build();
    }

    private Flux<WorkerMileageVisitResponse> toVisitResponsesWithMileage(List<WorkerMileageVisit> visits, boolean ascending) {
        Map<String, List<WorkerMileageVisit>> visitsByStaff = new LinkedHashMap<>();
        List<WorkerMileageVisit> orderedVisits = visits.stream()
                .sorted(Comparator.comparing(WorkerMileageVisit::getStaffNumber)
                        .thenComparing(WorkerMileageVisit::getVisitDate)
                        .thenComparing(visit -> visit.getId() == null ? 0L : visit.getId()))
                .toList();

        for (WorkerMileageVisit visit : orderedVisits) {
            visitsByStaff.computeIfAbsent(visit.getStaffNumber(), ignored -> new ArrayList<>()).add(visit);
        }

        Flux<WorkerMileageVisitResponse> responses = Flux.fromIterable(visitsByStaff.values())
                .concatMap(this::toStaffVisitResponsesWithMileage);

        return ascending ? responses : responses.sort(Comparator.comparing(WorkerMileageVisitResponse::getVisitDate).reversed()
                .thenComparing(visit -> visit.getId() == null ? 0L : visit.getId(), Comparator.reverseOrder()));
    }

    private Flux<WorkerMileageVisitResponse> toStaffVisitResponsesWithMileage(List<WorkerMileageVisit> visits) {
        double[] cumulativeMileage = {0.0};

        return Flux.range(0, visits.size())
                .concatMap(index -> {
                    WorkerMileageVisit current = visits.get(index);
                    if (index == 0) {
                        return Mono.just(toVisitResponse(current, null, 0.0, 0.0));
                    }

                    WorkerMileageVisit previous = visits.get(index - 1);
                    if (!Objects.equals(current.getVisitDate(), previous.getVisitDate())) {
                        cumulativeMileage[0] = 0.0;
                        return Mono.just(toVisitResponse(current, null, 0.0, 0.0));
                    }

                    return distanceBetween(previous, current)
                            .map(distance -> {
                                double roundedDistance = roundMileage(distance);
                                cumulativeMileage[0] = roundMileage(cumulativeMileage[0] + roundedDistance);
                                return toVisitResponse(current, previous.getAddress(), roundedDistance, cumulativeMileage[0]);
                            });
                });
    }

    private Mono<Double> distanceBetween(WorkerMileageVisit previous, WorkerMileageVisit current) {
        if (previous.getLat() == null || previous.getLon() == null
                || current.getLat() == null || current.getLon() == null) {
            return Mono.just(0.0);
        }

        return Mono.just(straightLineDistanceKm(
                new Location(previous.getLat(), previous.getLon()),
                new Location(current.getLat(), current.getLon())));
    }

    private WorkerMileageVisitResponse toVisitResponse(WorkerMileageVisit visit, String previousAddress,
                                                       double distanceFromPreviousKm, double cumulativeMileageKm) {
        return WorkerMileageVisitResponse.builder()
                .id(visit.getId())
                .reportId(visit.getReportId())
                .staffNumber(visit.getStaffNumber())
                .visitDate(visit.getVisitDate())
                .startTime(visit.getStartTime())
                .endTime(visit.getEndTime())
                .address(visit.getAddress())
                .lat(visit.getLat())
                .lon(visit.getLon())
                .previousAddress(previousAddress)
                .distanceFromPreviousKm(distanceFromPreviousKm)
                .distanceFromPreviousMiles(kmToMiles(distanceFromPreviousKm))
                .cumulativeMileageKm(cumulativeMileageKm)
                .cumulativeMileageMiles(kmToMiles(cumulativeMileageKm))
                .build();
    }

    private record VisitRow(
            String staffNumber,
            LocalDateTime visitDateTime,
            LocalDate visitDate,
            LocalTime startTime,
            LocalTime endTime,
            String address,
            String name,
            String shift,
            Double lat,
            Double lon
    ) {
        private VisitRow withLocation(Double lat, Double lon) {
            return new VisitRow(staffNumber, visitDateTime, visitDate, startTime, endTime, address, name, shift, lat, lon);
        }
    }

    private record Location(double lat, double lon) {
    }

    private record RoutePair(Location origin, Location destination) {
    }

    private record MileageResult(String staffNumber, double totalMileageKm, double totalOriginalMileageKm, double totalSavedMileageKm) {
    }

    private record ShiftKey(String staffNumber, LocalDate date, String shift) {
    }

    private record DailyKey(String staffNumber, LocalDate date) {
    }

    private record DailyMileageResult(
            String staffNumber,
            LocalDate date,
            double originalKm,
            double optimisedKm,
            double savedKm,
            List<String> sessions) {
    }

    private record ShiftResult(
            ShiftKey key,
            List<VisitRow> originalVisits,
            List<VisitRow> optimisedVisits,
            double originalKm,
            double optimisedKm,
            double savedKm,
            List<Double> originalLegsKm,
            List<Double> optimisedLegsKm) {
    }

    private record AnalysisResult(List<VisitRow> visits, List<MileageResult> rows, List<ShiftResult> shifts) {
    }

    private record MatrixBatch(List<String> origins, List<String> destinations) {
        private int elements() {
            return origins.size() * destinations.size();
        }
    }

    private record UploadJob(WorkerMileageUpload upload, Mono<Void> completion) {
    }

    private record CachedDistance(String origin, String destination, Double distanceKm) {
    }

    private static class GoogleRoutesMatrixRateLimitException extends RuntimeException {
        private GoogleRoutesMatrixRateLimitException(String message) {
            super(message);
        }
    }

    private record TimeRange(LocalTime startTime, LocalTime endTime) {
    }

    private record ReportMonthFilter(String exactReportMonth, Integer year, Integer month) {
    }

    private record ParsedMonthYear(int month, int year) {
    }

    private record ReportRows(WorkerMileageReport report, List<WorkerMileageReportRow> rows, List<WorkerMileageVisit> visits) {
    }

    private record ReportVisits(WorkerMileageReport report, List<WorkerMileageVisit> visits) {
    }

    private static class StaffMileageTotals {

        private double totalMileage;
        private double totalStraightLineMileage;

        private void add(double mileage, double straightLineMileage) {
            totalMileage += mileage;
            totalStraightLineMileage += straightLineMileage;
        }

        private double totalMileage() {
            return totalMileage;
        }

        private double totalStraightLineMileage() {
            return totalStraightLineMileage;
        }
    }

    private static class DailyMileageAccumulator {

        private double originalKm;
        private double optimisedKm;
        private double savedKm;
        private final List<String> sessions = new ArrayList<>();

        private void add(ShiftResult shift) {
            originalKm += shift.originalKm();
            optimisedKm += shift.optimisedKm();
            savedKm += shift.savedKm();
            sessions.add(shift.key().shift());
        }

        private double originalKm() {
            return originalKm;
        }

        private double optimisedKm() {
            return optimisedKm;
        }

        private double savedKm() {
            return savedKm;
        }

        private List<String> sessions() {
            return sessions;
        }
    }
}
