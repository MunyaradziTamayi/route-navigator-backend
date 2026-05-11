package com.routenav.service;

import com.routenav.dto.WorkerMileageReportResponse;
import com.routenav.dto.WorkerMileageReportRowResponse;
import com.routenav.dto.WorkerMileageVisitResponse;
import com.routenav.model.WorkerMileageReport;
import com.routenav.model.WorkerMileageReportRow;
import com.routenav.model.WorkerMileageVisit;
import com.routenav.repository.WorkerMileageReportRepository;
import com.routenav.repository.WorkerMileageReportRowRepository;
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

    private final WorkerMileageVisitRepository visitRepository;

    public MileageUploadService(GeocodingService geocodingService,
                                RoutingService routingService,
                                WorkerMileageReportRepository reportRepository,
                                WorkerMileageReportRowRepository reportRowRepository,
                                WorkerMileageVisitRepository visitRepository) {
        this.geocodingService = geocodingService;
        this.routingService = routingService;
        this.reportRepository = reportRepository;
        this.reportRowRepository = reportRowRepository;
        this.visitRepository = visitRepository;
    }

    public Mono<List<WorkerMileageReportResponse>> calculateMonthlyMileageReports(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> Mono.fromCallable(() -> parseVisits(bytes))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(visits -> Flux.fromIterable(groupVisitsByMonth(visits).entrySet()))
                .concatMap(entry -> geocodeVisits(entry.getValue())
                        .flatMap(this::calculateMileage)
                        .flatMap(results -> Mono.fromCallable(() -> buildWorkbook(results))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(workbook -> saveReport(reportMonth(entry.getKey()), entry.getValue(), results, workbook))))
                .collectList();
    }

    public Flux<WorkerMileageReportResponse> listReports(String reportMonth, Integer year) {
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

        return reports.flatMap(this::toReportResponse);
    }

    public Mono<WorkerMileageReportResponse> getReport(Long reportId) {
        return reportRepository.findById(reportId).flatMap(this::toReportResponse);
    }

    public Mono<WorkerMileageReport> getReportFile(Long reportId) {
        return reportRepository.findById(reportId);
    }

    public Flux<WorkerMileageVisitResponse> getVisitsByStaffNumber(String staffNumber) {
        return visitRepository.findByStaffNumberOrderByVisitDateDescStartTimeDesc(staffNumber)
                .map(this::toVisitResponse);
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

        ParsedMonthYear parsed = parseMonthYear(monthText)
                .orElseThrow(() -> new IllegalArgumentException("Use a month and year, for example: May 2026."));
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
            return Mono.just(new MileageResult(staffNumber, 0.0));
        }

        List<RoutePair> pairs = new ArrayList<>();
        for (int i = 1; i < locations.size(); i++) {
            pairs.add(new RoutePair(locations.get(i - 1), locations.get(i)));
        }

        return Flux.fromIterable(pairs)
                .concatMap(pair -> routingService.calculateDistanceKm(
                        pair.origin().lat(),
                        pair.origin().lon(),
                        pair.destination().lat(),
                        pair.destination().lon()))
                .reduce(0.0, Double::sum)
                .map(total -> new MileageResult(staffNumber, total));
    }

    private byte[] buildWorkbook(List<MileageResult> results) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Mileage Results");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Staff Number");
            header.createCell(1).setCellValue("Total Mileage");

            for (int i = 0; i < results.size(); i++) {
                MileageResult result = results.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(result.staffNumber());
                row.createCell(1).setCellValue(Math.round(result.totalMileageKm() * 100.0) / 100.0);
            }

            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private Mono<WorkerMileageReportResponse> saveReport(String reportMonth, List<VisitRow> visits, List<MileageResult> results, byte[] workbook) {
        WorkerMileageReport report = WorkerMileageReport.builder()
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
                        .totalMileage(Math.round(result.totalMileageKm() * 100.0) / 100.0)
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
        Mono<List<WorkerMileageReportRowResponse>> rows = reportRowRepository.findByReportIdOrderByStaffNumberAsc(report.getId())
                .map(row -> WorkerMileageReportRowResponse.builder()
                        .staffNumber(row.getStaffNumber())
                        .totalMileage(row.getTotalMileage())
                        .build())
                .collectList();
        Mono<List<WorkerMileageVisitResponse>> visits = visitRepository.findByReportIdOrderByStaffNumberAscVisitDateAscStartTimeAsc(report.getId())
                .map(this::toVisitResponse)
                .collectList();

        return Mono.zip(rows, visits)
                .map(tuple -> WorkerMileageReportResponse.builder()
                        .id(report.getId())
                        .reportMonth(report.getReportMonth())
                        .fileName(report.getFileName())
                        .downloadUrl("/api/mileage/reports/" + report.getId() + "/download")
                        .createdAt(report.getCreatedAt())
                        .rows(tuple.getT1())
                        .visits(tuple.getT2())
                        .build());
    }

    private WorkerMileageVisitResponse toVisitResponse(WorkerMileageVisit visit) {
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

    private record MileageResult(String staffNumber, double totalMileageKm) {
    }

    private record TimeRange(LocalTime startTime, LocalTime endTime) {
    }

    private record ReportMonthFilter(String exactReportMonth, Integer year, Integer month) {
    }

    private record ParsedMonthYear(int month, int year) {
    }
}
