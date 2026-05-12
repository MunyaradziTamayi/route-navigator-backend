package com.routenav.service;

import com.routenav.dto.WorkerMileageReportResponse;
import com.routenav.dto.WorkerMileageReportRowResponse;
import com.routenav.dto.WorkerMileageTotalResponse;
import com.routenav.dto.WorkerMileageTotalRowResponse;
import com.routenav.dto.WorkerMileageUploadResponse;
import com.routenav.dto.WorkerMileageVisitResponse;
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
import java.util.Optional;
import java.util.TreeMap;
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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class MileageUploadService {

    private static final double KM_TO_MILES = 0.621371;

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("M-d-yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy")
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

    public MileageUploadService(GeocodingService geocodingService,
                                RoutingService routingService,
                                WorkerMileageReportRepository reportRepository,
                                WorkerMileageReportRowRepository reportRowRepository,
                                WorkerMileageUploadRepository uploadRepository,
                                WorkerMileageVisitRepository visitRepository) {
        this.geocodingService = geocodingService;
        this.routingService = routingService;
        this.reportRepository = reportRepository;
        this.reportRowRepository = reportRowRepository;
        this.uploadRepository = uploadRepository;
        this.visitRepository = visitRepository;
    }

    public Mono<List<WorkerMileageReportResponse>> calculateMonthlyMileageReports(FilePart file) {
        return calculateMonthlyMileageReports(file, null);
    }

    private Mono<List<WorkerMileageReportResponse>> calculateMonthlyMileageReports(FilePart file, Long uploadId) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> Mono.fromCallable(() -> parseVisits(bytes))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(visits -> Flux.fromIterable(groupVisitsByMonth(visits).entrySet()))
                .concatMap(entry -> geocodeVisits(entry.getValue())
                        .flatMap(geocodedVisits -> calculateMileage(geocodedVisits)
                                .flatMap(results -> Mono.fromCallable(() -> buildWorkbook(results))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMap(workbook -> saveReport(uploadId, reportMonth(entry.getKey()), geocodedVisits, results, workbook)))))
                .collectList();
    }

    public Mono<List<WorkerMileageTotalResponse>> calculateMonthlyMileageTotals(FilePart file) {
        return calculateMonthlyMileageUpload(file)
                .map(WorkerMileageUploadResponse::getTotals);
    }

    public Mono<WorkerMileageUploadResponse> calculateMonthlyMileageUpload(FilePart file) {
        return saveUpload(file.filename())
                .flatMap(upload -> calculateMonthlyMileageReports(file, upload.getId())
                        .map(reports -> toUploadResponse(upload, reports, true)));
    }

    public Flux<WorkerMileageReportResponse> listReports(String reportMonth, Integer year) {
        return findReports(reportMonth, year).flatMap(this::toReportResponse);
    }

    public Flux<WorkerMileageTotalResponse> getMileageTotals(String reportMonth, Integer year) {
        return findReports(reportMonth, year)
                .flatMap(report -> reportRowRepository.findByReportIdOrderByStaffNumberAsc(report.getId())
                        .collectList()
                        .map(rows -> new ReportRows(report, rows)))
                .collectList()
                .flatMapMany(reportRows -> {
                    if (reportRows.isEmpty()) {
                        return Flux.empty();
                    }

                    Map<String, List<ReportRows>> reportsByMonth = new TreeMap<>();
                    for (ReportRows rows : reportRows) {
                        reportsByMonth.computeIfAbsent(rows.report().getReportMonth(), ignored -> new ArrayList<>()).add(rows);
                    }

                    List<WorkerMileageTotalResponse> totals = new ArrayList<>();
                    totals.add(toStoredMileageTotalResponse("all", "Total mileage for all selected months", reportRows));
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

    private Mono<WorkerMileageUpload> saveUpload(String fileName) {
        return uploadRepository.save(WorkerMileageUpload.builder()
                .fileName(fileName == null || fileName.isBlank() ? "worker-mileage-upload.xlsx" : fileName)
                .createdAt(LocalDateTime.now())
                .build());
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

                String staffNumber = getText(row, columns.get("staff number"), formatter);
                String address = getText(row, columns.get("address"), formatter);
                LocalDate date = parseDate(row.getCell(columns.get("date")), formatter);
                TimeRange visitTime = parseTimeRange(row.getCell(columns.get("visit time")), formatter);

                if (staffNumber.isBlank() || address.isBlank()) {
                    throw new IllegalArgumentException("Row " + (i + 1) + " must include Staff Number and Address.");
                }

                visits.add(new VisitRow(staffNumber, LocalDateTime.of(date, visitTime.startTime()), date, visitTime.startTime(), visitTime.endTime(), address, null, null));
            }

            if (visits.isEmpty()) {
                throw new IllegalArgumentException("No visit rows were found in the Excel file.");
            }

            return visits;
        }
    }

    private Map<String, Integer> findColumns(Row header, DataFormatter formatter) {
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : header) {
            String value = formatter.formatCellValue(cell).trim().toLowerCase(Locale.ENGLISH);
            if (!value.isBlank()) {
                columns.put(value, cell.getColumnIndex());
            }
        }

        for (String required : List.of("staff number", "date", "visit time", "address")) {
            if (!columns.containsKey(required)) {
                throw new IllegalArgumentException("Missing required column: " + required);
            }
        }

        return columns;
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
        LocalTime startTime = parseTimeText(parts[0].trim(), value);
        LocalTime endTime = parts.length > 1 && !parts[1].trim().isBlank()
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

    private Mono<List<VisitRow>> geocodeVisits(List<VisitRow> visits) {
        Map<String, Mono<Location>> addressCache = new HashMap<>();
        return Flux.fromIterable(visits)
                .concatMap(visit -> geocode(visit, addressCache)
                        .map(location -> visit.withLocation(location.lat(), location.lon())))
                .collectList();
    }

    private Mono<List<MileageResult>> calculateMileage(List<VisitRow> visits) {
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

        List<Location> locations = visits.stream()
                .filter(visit -> visit.lat() != null && visit.lon() != null)
                .map(visit -> new Location(visit.lat(), visit.lon()))
                .toList();

        return calculateLocationDistances(staffNumber, locations);
    }

    private Mono<Location> geocode(VisitRow visit, Map<String, Mono<Location>> addressCache) {
        return addressCache.computeIfAbsent(visit.address(), address -> geocodingService.geocodeFirst(address)
                .map(response -> new Location(response.getLat(), response.getLon()))
                .cache());
    }

    private Mono<MileageResult> calculateLocationDistances(String staffNumber, List<Location> locations) {
        if (locations.size() < 2) {
            return Mono.just(new MileageResult(staffNumber, 0.0, 0.0));
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
                .map(total -> new MileageResult(staffNumber, total, totalStraightLineMileageKm));
    }

    private Map<String, Double> straightLineMileageByStaff(List<WorkerMileageVisit> visits) {
        Map<String, List<WorkerMileageVisit>> visitsByStaff = new LinkedHashMap<>();
        for (WorkerMileageVisit visit : visits) {
            visitsByStaff.computeIfAbsent(visit.getStaffNumber(), ignored -> new ArrayList<>()).add(visit);
        }

        Map<String, Double> totals = new HashMap<>();
        for (Map.Entry<String, List<WorkerMileageVisit>> entry : visitsByStaff.entrySet()) {
            List<Location> locations = entry.getValue().stream()
                    .sorted(Comparator.comparing(WorkerMileageVisit::getVisitDate)
                            .thenComparing(WorkerMileageVisit::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())))
                    .filter(visit -> visit.getLat() != null && visit.getLon() != null)
                    .map(visit -> new Location(visit.getLat(), visit.getLon()))
                    .toList();
            totals.put(entry.getKey(), totalStraightLineMileageKm(locations));
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
        Map<String, Double> totalsByStaff = new TreeMap<>();
        double totalMileageKm = 0.0;

        for (WorkerMileageReportResponse report : reports) {
            for (WorkerMileageReportRowResponse row : report.getRows()) {
                double rowMileageKm = rowMileageKm(row);
                totalMileageKm += rowMileageKm;
                totalsByStaff.merge(row.getStaffNumber(), rowMileageKm, Double::sum);
            }
        }

        List<WorkerMileageTotalRowResponse> rows = totalsByStaff.entrySet().stream()
                .map(entry -> WorkerMileageTotalRowResponse.builder()
                        .staffNumber(entry.getKey())
                        .totalMileageMiles(kmToMiles(entry.getValue()))
                        .build())
                .toList();

        return WorkerMileageTotalResponse.builder()
                .reportMonth(reportMonth)
                .uploadId(uploadIdFromReportResponses(reports))
                .label(label)
                .totalMileageMiles(kmToMiles(totalMileageKm))
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

        for (ReportRows report : reports) {
            for (WorkerMileageReportRow row : report.rows()) {
                double rowMileage = row.getTotalMileage() == null ? 0.0 : row.getTotalMileage();
                totalMileage += rowMileage;

                totalsByStaff.computeIfAbsent(row.getStaffNumber(), ignored -> new StaffMileageTotals())
                        .add(rowMileage, 0.0);
            }
        }

        List<WorkerMileageTotalRowResponse> rows = totalsByStaff.entrySet().stream()
                .map(entry -> WorkerMileageTotalRowResponse.builder()
                        .staffNumber(entry.getKey())
                        .totalMileageMiles(kmToMiles(entry.getValue().totalMileage()))
                        .build())
                .toList();

        return WorkerMileageTotalResponse.builder()
                .reportMonth(reportMonth)
                .uploadId(uploadIdFromReportRows(reports))
                .label(label)
                .totalMileageMiles(kmToMiles(totalMileage))
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

    private byte[] buildWorkbook(List<MileageResult> results) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Mileage Results");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Staff Number");
            header.createCell(1).setCellValue("Total Driving Mileage");
            header.createCell(2).setCellValue("Total Straight-Line Mileage");

            for (int i = 0; i < results.size(); i++) {
                MileageResult result = results.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(result.staffNumber());
                row.createCell(1).setCellValue(roundMileage(result.totalMileageKm()));
                row.createCell(2).setCellValue(roundMileage(result.totalStraightLineMileageKm()));
            }

            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            sheet.autoSizeColumn(2);
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
        WorkerMileageReport report = WorkerMileageReport.builder()
                .uploadId(uploadId)
                .reportMonth(reportMonth)
                .fileName("worker-mileage-" + reportMonth + ".xlsx")
                .fileContent(workbook)
                .createdAt(LocalDateTime.now())
                .build();

        return reportRepository.save(report)
                .flatMap(saved -> saveReportRows(saved.getId(), results)
                        .then(saveReportVisits(saved.getId(), visits))
                        .then(toReportResponse(saved)));
    }

    private Mono<Void> saveReportRows(Long reportId, List<MileageResult> results) {
        return Flux.fromIterable(results)
                .map(result -> WorkerMileageReportRow.builder()
                        .reportId(reportId)
                        .staffNumber(result.staffNumber())
                        .totalMileage(roundMileage(result.totalMileageKm()))
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
                    Map<String, Double> straightLineMileageByStaff = straightLineMileageByStaff(tuple.getT2());
                    List<WorkerMileageReportRowResponse> rowResponses = tuple.getT1().stream()
                            .map(row -> WorkerMileageReportRowResponse.builder()
                                    .staffNumber(row.getStaffNumber())
                                    .totalMileage(row.getTotalMileage())
                                    .totalMileageKm(row.getTotalMileage())
                                    .totalMileageMiles(kmToMiles(row.getTotalMileage() == null ? 0.0 : row.getTotalMileage()))
                                    .totalStraightLineMileage(roundMileage(straightLineMileageByStaff.getOrDefault(row.getStaffNumber(), 0.0)))
                                    .totalStraightLineMileageKm(roundMileage(straightLineMileageByStaff.getOrDefault(row.getStaffNumber(), 0.0)))
                                    .totalStraightLineMileageMiles(kmToMiles(straightLineMileageByStaff.getOrDefault(row.getStaffNumber(), 0.0)))
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
            Double lat,
            Double lon
    ) {
        private VisitRow withLocation(Double lat, Double lon) {
            return new VisitRow(staffNumber, visitDateTime, visitDate, startTime, endTime, address, lat, lon);
        }
    }

    private record Location(double lat, double lon) {
    }

    private record RoutePair(Location origin, Location destination) {
    }

    private record MileageResult(String staffNumber, double totalMileageKm, double totalStraightLineMileageKm) {
    }

    private record TimeRange(LocalTime startTime, LocalTime endTime) {
    }

    private record ReportMonthFilter(String exactReportMonth, Integer year, Integer month) {
    }

    private record ParsedMonthYear(int month, int year) {
    }

    private record ReportRows(WorkerMileageReport report, List<WorkerMileageReportRow> rows) {
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
}
