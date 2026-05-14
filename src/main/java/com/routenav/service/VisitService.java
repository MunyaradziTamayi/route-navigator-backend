package com.routenav.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routenav.dto.VisitRequest;
import com.routenav.dto.VisitResponse;
import com.routenav.model.Visit;
import com.routenav.model.Worker;
import com.routenav.repository.VisitRepository;
import com.routenav.repository.WorkerRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class VisitService {

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

    private final VisitRepository visitRepository;
    private final ObjectMapper objectMapper;
    private final WorkerRepository workerRepository;

    public Flux<VisitResponse> list() {
        return visitRepository.findAllByOrderByCreatedAtDesc().map(this::toResponse);
    }

    public Mono<VisitResponse> create(VisitRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Visit visit = toEntity(request, null, now, now);
        return visitRepository.save(visit).map(this::toResponse);
    }

    public Mono<List<VisitResponse>> upload(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> Mono.fromCallable(() -> parseScheduleRows(bytes))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(rows -> Flux.fromIterable(rows)
                        .concatMap(this::saveUploadedVisit)
                        .collectList());
    }

    public Mono<List<VisitResponse>> assignStaff() {
        Mono<List<Worker>> workers = workerRepository.findAllByOrderByCreatedAtDesc()
                .sort(Comparator.comparing(Worker::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .collectList();

        Mono<List<Visit>> unassignedVisits = visitRepository.findAll()
                .filter(this::isUnassigned)
                .sort(Comparator.comparing(Visit::getScheduledDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Visit::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(visit -> visit.getId() == null ? 0L : visit.getId()))
                .collectList();

        return Mono.zip(workers, unassignedVisits)
                .flatMap(tuple -> {
                    List<Worker> availableWorkers = tuple.getT1();
                    List<Visit> visits = tuple.getT2();
                    if (availableWorkers.isEmpty()) {
                        return Mono.error(new IllegalArgumentException("Add workers before assigning visits."));
                    }
                    if (visits.isEmpty()) {
                        return Mono.just(List.of());
                    }

                    LocalDateTime now = LocalDateTime.now();
                    for (int i = 0; i < visits.size(); i++) {
                        Visit visit = visits.get(i);
                        Worker worker = availableWorkers.get(i % availableWorkers.size());
                        visit.setWorkerIds(toJson(List.of(String.valueOf(worker.getId()))));
                        visit.setWorkerNames(toJson(List.of(worker.getName())));
                        visit.setUpdatedAt(now);
                    }

                    return Flux.fromIterable(visits)
                            .concatMap(visitRepository::save)
                            .map(this::toResponse)
                            .collectList();
                });
    }

    public Mono<byte[]> buildScheduleWorkbook(LocalDate date) {
        return visitRepository.findAll()
                .filter(visit -> date == null || (visit.getScheduledDate() != null
                        && date.equals(visit.getScheduledDate().toLocalDate())))
                .sort(Comparator.comparing(Visit::getScheduledDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Visit::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(visit -> visit.getId() == null ? 0L : visit.getId()))
                .collectList()
                .flatMap(visits -> Mono.fromCallable(() -> buildScheduleWorkbookBytes(visits))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<VisitResponse> update(Long id, VisitRequest request) {
        return visitRepository.findById(id)
                .flatMap(existing -> {
                    Visit visit = toEntity(
                            request,
                            id,
                            existing.getCreatedAt() == null ? LocalDateTime.now() : existing.getCreatedAt(),
                            LocalDateTime.now()
                    );
                    return visitRepository.save(visit);
                })
                .map(this::toResponse);
    }

    public Mono<Void> delete(Long id) {
        return visitRepository.deleteById(id);
    }

    private Mono<VisitResponse> saveUploadedVisit(ScheduleVisitRow row) {
        int defaultDurationMinutes = 30;
        LocalTime endTime = row.endTime() == null ? row.startTime().plusMinutes(defaultDurationMinutes) : row.endTime();
        int durationSeconds = durationSeconds(row.startTime(), endTime, defaultDurationMinutes);
        LocalDateTime now = LocalDateTime.now();

        Visit visit = Visit.builder()
                .originLabel(null)
                .destinationLabel(row.address())
                .originLat(null)
                .originLon(null)
                .destinationLat(null)
                .destinationLon(null)
                .clientLabel(row.address())
                .notes("")
                .scheduledDate(LocalDateTime.of(row.date(), row.startTime()))
                .workerIds(toJson(List.of()))
                .workerNames(toJson(List.of()))
                .startTime(row.startTime())
                .endTime(endTime)
                .visitsPerDay(1)
                .distanceKm(0.0)
                .durationSeconds(durationSeconds)
                .geometry(toJson(null))
                .status("pending")
                .createdAt(now)
                .updatedAt(now)
                .build();

        return visitRepository.save(visit).map(this::toResponse);
    }

    private Visit toEntity(VisitRequest request, Long id, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return Visit.builder()
                .id(id)
                .originLabel(request.getOrigin().getLabel())
                .destinationLabel(request.getDestination().getLabel())
                .originLat(request.getOrigin().getLat())
                .originLon(request.getOrigin().getLon())
                .destinationLat(request.getDestination().getLat())
                .destinationLon(request.getDestination().getLon())
                .clientLabel(request.getClientLabel())
                .notes(request.getNotes())
                .scheduledDate(request.getScheduledDate())
                .workerIds(toJson(request.getWorkerIds()))
                .workerNames(toJson(request.getWorkerNames()))
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .visitsPerDay(request.getVisitsPerDay() == null ? 1 : Math.max(1, request.getVisitsPerDay()))
                .distanceKm(request.getDistanceKm())
                .durationSeconds(request.getDurationSeconds())
                .geometry(toJson(request.getGeoJson()))
                .status(request.getStatus() == null ? "pending" : request.getStatus())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private VisitResponse toResponse(Visit visit) {
        return VisitResponse.builder()
                .id(visit.getId())
                .originLabel(visit.getOriginLabel())
                .destinationLabel(visit.getDestinationLabel())
                .originLat(visit.getOriginLat())
                .originLon(visit.getOriginLon())
                .destinationLat(visit.getDestinationLat())
                .destinationLon(visit.getDestinationLon())
                .workerIds(fromJsonList(visit.getWorkerIds()))
                .workerNames(fromJsonList(visit.getWorkerNames()))
                .startTime(visit.getStartTime())
                .endTime(visit.getEndTime())
                .visitsPerDay(visit.getVisitsPerDay())
                .distanceKm(visit.getDistanceKm())
                .straightLineDistanceKm(straightLineDistanceKm(visit))
                .durationSeconds(visit.getDurationSeconds())
                .geoJson(fromJsonObject(visit.getGeometry()))
                .clientLabel(visit.getClientLabel())
                .notes(visit.getNotes())
                .scheduledDate(visit.getScheduledDate())
                .status(visit.getStatus())
                .createdAt(visit.getCreatedAt())
                .updatedAt(visit.getUpdatedAt())
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }

    private boolean isUnassigned(Visit visit) {
        return fromJsonList(visit.getWorkerIds()).isEmpty();
    }

    private byte[] toBytes(DataBuffer dataBuffer) {
        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);
        DataBufferUtils.release(dataBuffer);
        return bytes;
    }

    private List<ScheduleVisitRow> parseScheduleRows(byte[] bytes) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("The Excel file is empty.");
            }

            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = findColumns(header, formatter);
            List<ScheduleVisitRow> visits = new ArrayList<>();

            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }

                LocalDate date = parseDate(row.getCell(columns.get("date")), formatter);
                TimeRange visitTime = parseTimeRange(row.getCell(columns.get("visit time")), formatter);
                String address = getText(row, columns.get("address"), formatter);
                if (address.isBlank()) {
                    throw new IllegalArgumentException("Row " + (i + 1) + " must include Address.");
                }

                visits.add(new ScheduleVisitRow(date, visitTime.startTime(), visitTime.endTime(), address));
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

        for (String required : List.of("date", "visit time", "address")) {
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
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
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

    private int durationSeconds(LocalTime startTime, LocalTime endTime, int fallbackMinutes) {
        long seconds = Duration.between(startTime, endTime).getSeconds();
        return (int) (seconds > 0 ? seconds : fallbackMinutes * 60L);
    }

    private byte[] buildScheduleWorkbookBytes(List<Visit> visits) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Schedule");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            List<String> headers = List.of("Date", "Visit Time", "Address", "Assigned Worker(s)", "Status", "Notes");
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (Visit visit : visits) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(formatScheduleDate(visit));
                row.createCell(1).setCellValue(formatVisitTimeRange(visit));
                row.createCell(2).setCellValue(blankToDefault(visit.getDestinationLabel(), visit.getClientLabel()));
                row.createCell(3).setCellValue(workerNamesLabel(visit));
                row.createCell(4).setCellValue(blankToDefault(visit.getStatus(), "pending"));
                row.createCell(5).setCellValue(blankToDefault(visit.getNotes(), ""));
            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private String formatScheduleDate(Visit visit) {
        if (visit.getScheduledDate() != null) {
            return visit.getScheduledDate().toLocalDate().toString();
        }
        return "";
    }

    private String formatVisitTimeRange(Visit visit) {
        if (visit.getStartTime() != null && visit.getEndTime() != null) {
            return visit.getStartTime() + " - " + visit.getEndTime();
        }
        if (visit.getStartTime() != null) {
            return visit.getStartTime().toString();
        }
        return visit.getEndTime() == null ? "" : visit.getEndTime().toString();
    }

    private String workerNamesLabel(Visit visit) {
        List<String> workerNames = fromJsonList(visit.getWorkerNames());
        return workerNames.isEmpty() ? "Unassigned" : String.join(", ", workerNames);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private List<String> fromJsonList(String value) {
        try {
            if (value == null || value.isBlank()) return List.of();
            return objectMapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Object fromJsonObject(String value) {
        try {
            if (value == null || value.isBlank() || "null".equals(value)) return null;
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private double straightLineDistanceKm(Visit visit) {
        if (visit.getOriginLat() == null || visit.getOriginLon() == null
                || visit.getDestinationLat() == null || visit.getDestinationLon() == null) {
            return 0.0;
        }
        return haversineDistanceKm(visit.getOriginLat(), visit.getOriginLon(), visit.getDestinationLat(), visit.getDestinationLon());
    }

    private double haversineDistanceKm(double originLat, double originLon, double destinationLat, double destinationLon) {
        final double earthRadiusKm = 6371.0088;
        double latDistance = Math.toRadians(destinationLat - originLat);
        double lonDistance = Math.toRadians(destinationLon - originLon);
        double originLatRadians = Math.toRadians(originLat);
        double destinationLatRadians = Math.toRadians(destinationLat);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(originLatRadians) * Math.cos(destinationLatRadians)
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private record ScheduleVisitRow(LocalDate date, LocalTime startTime, LocalTime endTime, String address) {
    }

    private record TimeRange(LocalTime startTime, LocalTime endTime) {
    }
}
