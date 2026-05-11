package com.routenav.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public MileageUploadService(GeocodingService geocodingService, RoutingService routingService) {
        this.geocodingService = geocodingService;
        this.routingService = routingService;
    }

    public Mono<byte[]> calculateMonthlyMileage(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> Mono.fromCallable(() -> parseVisits(bytes))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(this::calculateMileage)
                .flatMap(results -> Mono.fromCallable(() -> buildWorkbook(results))
                        .subscribeOn(Schedulers.boundedElastic()));
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
                LocalTime visitTime = parseTime(row.getCell(columns.get("visit time")), formatter);

                if (staffNumber.isBlank() || address.isBlank()) {
                    throw new IllegalArgumentException("Row " + (i + 1) + " must include Staff Number and Address.");
                }

                visits.add(new VisitRow(staffNumber, LocalDateTime.of(date, visitTime), address));
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

    private LocalTime parseTime(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            throw new IllegalArgumentException("Visit Time is required.");
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalTime();
            }
            int seconds = (int) Math.round(cell.getNumericCellValue() * 24 * 60 * 60);
            return LocalTime.ofSecondOfDay(Math.floorMod(seconds, 24 * 60 * 60));
        }

        String value = formatter.formatCellValue(cell).trim().toUpperCase(Locale.ENGLISH);
        String startTime = value.split("\\s*-\\s*", 2)[0].trim();
        for (DateTimeFormatter timeFormatter : TIME_FORMATTERS) {
            try {
                return LocalTime.parse(startTime, timeFormatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        throw new IllegalArgumentException("Invalid visit time value: " + value);
    }

    private Mono<List<MileageResult>> calculateMileage(List<VisitRow> visits) {
        Map<String, List<VisitRow>> visitsByStaff = new LinkedHashMap<>();
        Map<String, Mono<Location>> addressCache = new HashMap<>();
        for (VisitRow visit : visits) {
            visitsByStaff.computeIfAbsent(visit.staffNumber(), ignored -> new ArrayList<>()).add(visit);
        }

        return Flux.fromIterable(visitsByStaff.entrySet())
                .concatMap(entry -> calculateStaffMileage(entry.getKey(), entry.getValue(), addressCache))
                .collectList();
    }

    private Mono<MileageResult> calculateStaffMileage(String staffNumber, List<VisitRow> visits, Map<String, Mono<Location>> addressCache) {
        visits.sort(Comparator.comparing(VisitRow::visitDateTime));

        return Flux.fromIterable(visits)
                .concatMap(visit -> geocode(visit, addressCache))
                .collectList()
                .flatMap(locations -> calculateLocationDistances(staffNumber, locations));
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

    private record VisitRow(String staffNumber, LocalDateTime visitDateTime, String address) {
    }

    private record Location(double lat, double lon) {
    }

    private record RoutePair(Location origin, Location destination) {
    }

    private record MileageResult(String staffNumber, double totalMileageKm) {
    }
}
