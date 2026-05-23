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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.HexFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class MileageUploadService {

    private static final double KM_TO_MILES = 0.621371;
    private static final double METERS_PER_KM = 1000.0;
    private static final int BRUTE_FORCE_LIMIT = 9;
    private static final int BATCH_MAX_DIM = 25;
    private static final int BATCH_MAX_ELEM = 100;

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
            DateTimeFormatter.ofPattern("M-d-yyyy")
    );

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH)
    );

    private final GeocodingService geocodingService;

    private final RoutingService routingService;

    private final WorkerMileageReportRepository reportRepository;

    private final WorkerMileageReportRowRepository reportRowRepository;

    private final WorkerMileageUploadRepository uploadRepository;

    private final WorkerMileageVisitRepository visitRepository;

    private final WebClient googleMapsClient;

    private final String googleApiKey;

    public MileageUploadService(GeocodingService geocodingService,
                                RoutingService routingService,
                                WorkerMileageReportRepository reportRepository,
                                WorkerMileageReportRowRepository reportRowRepository,
                                WorkerMileageUploadRepository uploadRepository,
                                WorkerMileageVisitRepository visitRepository,
                                @Qualifier("googleGeocodingClient") WebClient googleMapsClient,
                                @Value("${google.api-key}") String googleApiKey) {
        this.geocodingService = geocodingService;
        this.routingService = routingService;
        this.reportRepository = reportRepository;
        this.reportRowRepository = reportRowRepository;
        this.uploadRepository = uploadRepository;
        this.visitRepository = visitRepository;
        this.googleMapsClient = googleMapsClient;
        this.googleApiKey = googleApiKey;
    }

    public Mono<List<WorkerMileageReportResponse>> calculateMonthlyMileageReports(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> calculateMonthlyMileageReports(bytes, null));
    }

    private Mono<List<WorkerMileageReportResponse>> calculateMonthlyMileageReports(byte[] bytes, Long uploadId) {
        return Mono.fromCallable(() -> parseVisits(bytes))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(visits -> Flux.fromIterable(groupVisitsByMonth(visits).entrySet()))
                .concatMap(entry -> calculateMileage(entry.getValue())
                        .flatMap(analysis -> Mono.fromCallable(() -> buildWorkbook(analysis))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(workbook -> saveReport(uploadId, reportMonth(entry.getKey()), entry.getValue(), analysis.rows(), workbook))))
                .collectList();
    }

    public Mono<List<WorkerMileageTotalResponse>> calculateMonthlyMileageTotals(FilePart file) {
        return calculateMonthlyMileageUpload(file)
                .map(WorkerMileageUploadResponse::getTotals);
    }

    public Mono<WorkerMileageUploadResponse> calculateMonthlyMileageUpload(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> {
                    String fileHash = sha256Hex(bytes);
                    return uploadRepository.findFirstByFileHashOrderByCreatedAtDesc(fileHash)
                            .flatMap(existingUpload -> getUpload(existingUpload.getId()))
                            .switchIfEmpty(saveUpload(file.filename(), fileHash)
                                    .flatMap(upload -> calculateMonthlyMileageReports(bytes, upload.getId())
                                            .map(reports -> toUploadResponse(upload, reports, true))));
                });
    }

    public Flux<WorkerMileageReportResponse> listReports(String reportMonth, Integer year) {
        return findReports(reportMonth, year).flatMap(this::toReportResponse);
    }

    public Flux<WorkerMileageTotalResponse> getMileageTotals(String reportMonth, Integer year) {
        return findReports(reportMonth, year)
                .flatMap(report -> Mono.zip(
                                reportRowRepository.findByReportIdOrderByStaffNumberAsc(report.getId()).collectList(),
                                visitRepository.findByReportIdOrderByStaffNumberAscVisitDateAscStartTimeAsc(report.getId()).collectList())
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

    public Flux<WorkerMileageUploadResponse> listUploads(LocalDate uploadDate) {
        Flux<WorkerMileageUpload> uploads = uploadDate == null
                ? uploadRepository.findAllByOrderByCreatedAtDesc()
                : uploadRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(uploadDate.atStartOfDay(), uploadDate.plusDays(1).atStartOfDay());

        return uploads.flatMap(upload -> getUpload(upload.getId())
                .defaultIfEmpty(toUploadResponse(upload, List.of(), false)));
    }

    public Mono<WorkerMileageUploadResponse> getUpload(Long uploadId) {
        return uploadRepository.findById(uploadId)
                .flatMap(upload -> reportRepository.findByUploadIdOrderByReportMonthAsc(uploadId)
                        .flatMap(this::toReportResponse)
                        .collectList()
                        .map(reports -> toUploadResponse(upload, reports, true)));
    }

    private Mono<WorkerMileageUpload> saveUpload(String fileName, String fileHash) {
        return uploadRepository.save(WorkerMileageUpload.builder()
                .fileName(fileName == null || fileName.isBlank() ? "worker-mileage-upload.xlsx" : fileName)
                .fileHash(fileHash)
                .createdAt(LocalDateTime.now())
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

    public Mono<WorkerMileageReportResponse> getReport(Long reportId) {
        return reportRepository.findById(reportId).flatMap(this::toReportResponse);
    }

    public Mono<WorkerMileageReport> getReportFile(Long reportId) {
        return reportRepository.findById(reportId);
    }

    public Flux<WorkerMileageVisitResponse> getVisitsByStaffNumber(String staffNumber) {
        return visitRepository.findByStaffNumberOrderByVisitDateDescStartTimeDesc(staffNumber)
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
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("The Excel file is empty.");
            }

            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = findColumns(header, formatter);
            List<VisitRow> visits = new ArrayList<>();

            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }

                String staffNumber = getText(row, columns.get("Carer_No"), formatter);
                String address = getText(row, columns.get("Client Address"), formatter);
                LocalDate date = parseDate(row.getCell(columns.get("Date_of_Call")), formatter);
                TimeRange visitTime = parseTimeRange(row.getCell(columns.get("Time_of_Call")), formatter);
                if (visitTime == null || visitTime.startTime() == null) {
                    continue;
                }
                String name = columns.containsKey("Name") ? getText(row, columns.get("Name"), formatter) : "";
                String shift = columns.containsKey("Shifts") ? normalizeShift(getText(row, columns.get("Shifts"), formatter), visitTime.startTime()) : shiftForTime(visitTime.startTime());

                if (staffNumber.isBlank() || address.isBlank()) {
                    continue;
                }

                visits.add(new VisitRow(staffNumber, LocalDateTime.of(date, visitTime.startTime()), date, visitTime.startTime(), visitTime.endTime(), address, name, shift, null, null));
            }

            if (visits.isEmpty()) {
                throw new IllegalArgumentException("No visit rows were found in the Excel file.");
            }

            return visits;
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
        String[] parts = value.split("\\s*-\\s*", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            LocalTime startTime = parseTimeText(parts[0].trim(), value);
            LocalTime endTime = !parts[1].trim().isBlank()
                    ? parseTimeText(parts[1].trim(), value)
                    : null;
            return new TimeRange(startTime, endTime);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
            visitsByShift.computeIfAbsent(new ShiftKey(visit.staffNumber(), visit.visitDate(), visit.shift()), ignored -> new ArrayList<>()).add(visit);
        }

        return Flux.fromIterable(visitsByShift.entrySet())
                .concatMap(entry -> analyseShift(entry.getKey(), entry.getValue()))
                .collectList()
                .map(shiftResults -> toAnalysisResult(orderedVisits, shiftResults));
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

    private Mono<ShiftResult> analyseShift(ShiftKey key, List<VisitRow> visits) {
        List<String> addresses = visits.stream().map(VisitRow::address).toList();
        if (addresses.size() < 2) {
            return Mono.just(new ShiftResult(key, visits, visits, 0.0, 0.0, 0.0, List.of(), List.of()));
        }

        return distanceMatrix(addresses)
                .map(matrix -> {
                    List<Double> originalLegs = new ArrayList<>();
                    for (int i = 0; i < addresses.size() - 1; i++) {
                        originalLegs.add(matrix[i][i + 1]);
                    }
                    double originalKm = originalLegs.stream().mapToDouble(Double::doubleValue).sum();
                    List<Integer> optIndexes = optimisedIndexes(matrix);
                    List<VisitRow> optimisedVisits = optIndexes.stream().map(visits::get).toList();
                    List<Double> optimisedLegs = new ArrayList<>();
                    for (int i = 0; i < optIndexes.size() - 1; i++) {
                        optimisedLegs.add(matrix[optIndexes.get(i)][optIndexes.get(i + 1)]);
                    }
                    double optimisedKm = optimisedLegs.stream().mapToDouble(Double::doubleValue).sum();
                    return new ShiftResult(key, visits, optimisedVisits, originalKm, optimisedKm, originalKm - optimisedKm, originalLegs, optimisedLegs);
                });
    }

    private Mono<double[][]> distanceMatrix(List<String> addresses) {
        int size = addresses.size();
        double[][] distances = new double[size][size];
        List<MatrixBatch> batches = new ArrayList<>();
        for (int dStart = 0; dStart < size; dStart += BATCH_MAX_DIM) {
            int dEnd = Math.min(size, dStart + BATCH_MAX_DIM);
            int destinationCount = dEnd - dStart;
            int maxOrigins = Math.max(1, Math.min(BATCH_MAX_DIM, BATCH_MAX_ELEM / destinationCount));
            for (int oStart = 0; oStart < size; oStart += maxOrigins) {
                int oEnd = Math.min(size, oStart + maxOrigins);
                batches.add(new MatrixBatch(oStart, oEnd, dStart, dEnd));
            }
        }

        return Flux.fromIterable(batches)
                .concatMap(batch -> fetchDistanceBatch(addresses, batch)
                        .doOnNext(response -> copyDistanceBatch(distances, batch, response)))
                .then(Mono.just(distances));
    }

    private Mono<GoogleDistanceMatrixResponse> fetchDistanceBatch(List<String> addresses, MatrixBatch batch) {
        return googleMapsClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/maps/api/distancematrix/json")
                        .queryParam("origins", String.join("|", addresses.subList(batch.originStart(), batch.originEnd())))
                        .queryParam("destinations", String.join("|", addresses.subList(batch.destinationStart(), batch.destinationEnd())))
                        .queryParam("mode", "driving")
                        .queryParam("units", "metric")
                        .queryParam("key", requireGoogleApiKey())
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "Google Distance Matrix error (" + response.statusCode().value() + "): " + body))))
                .bodyToMono(GoogleDistanceMatrixResponse.class);
    }

    private void copyDistanceBatch(double[][] distances, MatrixBatch batch, GoogleDistanceMatrixResponse response) {
        if (response == null || !"OK".equals(response.getStatus())) {
            throw new IllegalStateException("Google Distance Matrix returned status: " + (response == null ? "UNKNOWN" : response.getStatus()));
        }
        if (response.getRows() == null) {
            throw new IllegalStateException("Google Distance Matrix response missing rows.");
        }
        for (int rowIndex = 0; rowIndex < batch.originEnd() - batch.originStart(); rowIndex++) {
            GoogleDistanceMatrixResponse.GoogleDistanceMatrixRow row = response.getRows().get(rowIndex);
            for (int columnIndex = 0; columnIndex < batch.destinationEnd() - batch.destinationStart(); columnIndex++) {
                int i = batch.originStart() + rowIndex;
                int j = batch.destinationStart() + columnIndex;
                if (i == j || row == null || row.getElements() == null || columnIndex >= row.getElements().size()) {
                    distances[i][j] = 0.0;
                    continue;
                }
                GoogleDistanceMatrixResponse.GoogleDistanceMatrixElement element = row.getElements().get(columnIndex);
                distances[i][j] = element != null && "OK".equals(element.getStatus()) && element.getDistance() != null && element.getDistance().getValue() != null
                        ? element.getDistance().getValue() / METERS_PER_KM
                        : 0.0;
            }
        }
    }

    private String requireGoogleApiKey() {
        if (!StringUtils.hasText(googleApiKey)) {
            throw new IllegalStateException("GOOGLE_API_KEY environment variable is required.");
        }
        return googleApiKey;
    }

    private List<Integer> optimisedIndexes(double[][] matrix) {
        int n = matrix.length;
        if (n <= BRUTE_FORCE_LIMIT) {
            BestPath best = new BestPath(null, Double.MAX_VALUE);
            permute(new ArrayList<>(), n, new boolean[n], matrix, best);
            return best.path();
        }
        return nearestNeighbourThenTwoOpt(matrix);
    }

    private void permute(List<Integer> path, int n, boolean[] used, double[][] matrix, BestPath best) {
        if (path.size() == n) {
            double total = pathCost(path, matrix);
            if (total < best.total()) {
                best.replace(new ArrayList<>(path), total);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            if (!used[i]) {
                used[i] = true;
                path.add(i);
                permute(path, n, used, matrix, best);
                path.remove(path.size() - 1);
                used[i] = false;
            }
        }
    }

    private List<Integer> nearestNeighbourThenTwoOpt(double[][] matrix) {
        int n = matrix.length;
        List<Integer> best = null;
        double bestTotal = Double.MAX_VALUE;
        for (int start = 0; start < n; start++) {
            List<Integer> path = nearestNeighbourPath(matrix, start);
            path = twoOpt(path, matrix);
            double total = pathCost(path, matrix);
            if (total < bestTotal) {
                bestTotal = total;
                best = path;
            }
        }
        return best == null ? List.of() : best;
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
            YearMonth parsed = YearMonth.parse(monthText);
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
                            .thenComparing(WorkerMileageVisit::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())))
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
                .reportCount(reports.size())
                .reportMonths(reportMonths)
                .totals(toUploadedMileageTotalResponses(reports))
                .reports(includeReports ? reports : List.of())
                .build();
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
                double rowStraightLineMileage = straightLineMileageByStaff.containsKey(row.getStaffNumber())
                        ? straightLineMileageByStaff.get(row.getStaffNumber())
                        : storedStraightLineMileage;
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
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            writeOptimisedScheduleSheet(workbook, analysis);
            writeCarerTotalsSheet(workbook, analysis.rows());
            writeDailyTotalsSheet(workbook, analysis.shifts());
            writeShiftBreakdownSheet(workbook, analysis.shifts());
            writeLegsSheet(workbook, analysis.shifts());
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private void writeOptimisedScheduleSheet(Workbook workbook, AnalysisResult analysis) {
        Sheet sheet = workbook.createSheet("Optimised Schedule");
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

    private void writeCarerTotalsSheet(Workbook workbook, List<MileageResult> results) {
        Sheet sheet = workbook.createSheet("Carer Totals");
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
        Sheet sheet = workbook.createSheet("Daily Totals");
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
        Sheet sheet = workbook.createSheet("Shift Breakdown");
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
        Sheet sheet = workbook.createSheet("Legs");
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
        String start = visit.startTime() == null ? "" : visit.startTime().format(DateTimeFormatter.ofPattern("HH:mm"));
        String end = visit.endTime() == null ? "" : visit.endTime().format(DateTimeFormatter.ofPattern("HH:mm"));
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

    private void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private byte[] buildWorkbookFromRows(List<WorkerMileageReportRow> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Mileage Results");
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

    private Long uploadIdFromReportRows(List<ReportRows> reports) {
        return reports.stream()
                .map(reportRows -> reportRows.report().getUploadId())
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
    }

    private Mono<WorkerMileageReportResponse> saveReport(Long uploadId, String reportMonth, List<VisitRow> visits, List<MileageResult> results, byte[] workbook) {
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
                        .then(toReportResponse(saved)));
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
                .flatMap(visitRepository::save)
                .then();
    }

    private Mono<WorkerMileageReportResponse> toReportResponse(WorkerMileageReport report) {
        Mono<List<WorkerMileageReportRow>> rows = reportRowRepository.findByReportIdOrderByStaffNumberAsc(report.getId())
                .collectList();
        Mono<List<WorkerMileageVisit>> visits = visitRepository.findByReportIdOrderByStaffNumberAscVisitDateAscStartTimeAsc(report.getId())
                .collectList();

        return Mono.zip(rows, visits)
                .flatMap(tuple -> {
                    List<WorkerMileageReportRowResponse> rowResponses = tuple.getT1().stream()
                            .map(row -> WorkerMileageReportRowResponse.builder()
                                    .staffNumber(row.getStaffNumber())
                                    .totalMileage(row.getTotalMileage())
                                    .totalMileageKm(row.getTotalMileage())
                                    .totalMileageMiles(kmToMiles(row.getTotalMileage() == null ? 0.0 : row.getTotalMileage()))
                                    .totalStraightLineMileage(row.getTotalStraightLineMileage())
                                    .totalStraightLineMileageKm(row.getTotalStraightLineMileage())
                                    .totalStraightLineMileageMiles(kmToMiles(row.getTotalStraightLineMileage() == null ? 0.0 : row.getTotalStraightLineMileage()))
                                    .build())
                            .toList();
                    return toVisitResponsesWithMileage(tuple.getT2(), true)
                            .collectList()
                            .map(visitResponses -> WorkerMileageReportResponse.builder()
                                    .id(report.getId())
                                    .uploadId(report.getUploadId())
                                    .reportMonth(report.getReportMonth())
                                    .fileName(report.getFileName())
                                    .downloadUrl("/api/mileage/reports/" + report.getId() + "/download")
                                    .createdAt(report.getCreatedAt())
                                    .rows(rowResponses)
                                    .visits(visitResponses)
                                    .build());
                });
    }

    private Flux<WorkerMileageVisitResponse> toVisitResponsesWithMileage(List<WorkerMileageVisit> visits, boolean ascending) {
        Map<String, List<WorkerMileageVisit>> visitsByStaff = new LinkedHashMap<>();
        List<WorkerMileageVisit> orderedVisits = visits.stream()
                .sorted(Comparator.comparing(WorkerMileageVisit::getStaffNumber)
                        .thenComparing(WorkerMileageVisit::getVisitDate)
                        .thenComparing(WorkerMileageVisit::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(visit -> visit.getId() == null ? 0L : visit.getId()))
                .toList();

        for (WorkerMileageVisit visit : orderedVisits) {
            visitsByStaff.computeIfAbsent(visit.getStaffNumber(), ignored -> new ArrayList<>()).add(visit);
        }

        Flux<WorkerMileageVisitResponse> responses = Flux.fromIterable(visitsByStaff.values())
                .concatMap(this::toStaffVisitResponsesWithMileage);

        return ascending ? responses : responses.sort(Comparator.comparing(WorkerMileageVisitResponse::getVisitDate).reversed()
                .thenComparing(WorkerMileageVisitResponse::getStartTime, Comparator.nullsLast(Comparator.reverseOrder())));
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
        if (StringUtils.hasText(previous.getAddress()) && StringUtils.hasText(current.getAddress())) {
            return distanceMatrix(List.of(previous.getAddress(), current.getAddress()))
                    .map(matrix -> matrix[0][1])
                    .defaultIfEmpty(0.0)
                    .onErrorReturn(0.0);
        }
        if (previous.getLat() == null || previous.getLon() == null
                || current.getLat() == null || current.getLon() == null) {
            return Mono.just(0.0);
        }

        return routingService.calculateDistanceKm(previous.getLat(), previous.getLon(), current.getLat(), current.getLon())
                .defaultIfEmpty(0.0)
                .onErrorReturn(0.0);
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

    private record MatrixBatch(int originStart, int originEnd, int destinationStart, int destinationEnd) {
    }

    private record TimeRange(LocalTime startTime, LocalTime endTime) {
    }

    private record ReportMonthFilter(String exactReportMonth, Integer year, Integer month) {
    }

    private record ParsedMonthYear(int month, int year) {
    }

    private record ReportRows(WorkerMileageReport report, List<WorkerMileageReportRow> rows, List<WorkerMileageVisit> visits) {
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

    private static class BestPath {

        private List<Integer> path;
        private double total;

        private BestPath(List<Integer> path, double total) {
            this.path = path;
            this.total = total;
        }

        private List<Integer> path() {
            return path == null ? List.of() : path;
        }

        private double total() {
            return total;
        }

        private void replace(List<Integer> path, double total) {
            this.path = path;
            this.total = total;
        }
    }
}
