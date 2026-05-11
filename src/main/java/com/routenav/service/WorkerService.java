package com.routenav.service;

import com.routenav.dto.WorkerRequest;
import com.routenav.dto.WorkerResponse;
import com.routenav.model.Worker;
import com.routenav.repository.WorkerRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
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
@RequiredArgsConstructor
public class WorkerService {

    private final WorkerRepository workerRepository;

    public Flux<WorkerResponse> list() {
        return workerRepository.findAllByOrderByCreatedAtDesc().map(this::toResponse);
    }

    public Mono<WorkerResponse> getById(Long id) {
        return workerRepository.findById(id).map(this::toResponse);
    }

    public Mono<WorkerResponse> create(WorkerRequest request) {
        if (request.getStaffNumber() == null || request.getStaffNumber().isBlank()) {
            return Mono.error(new IllegalArgumentException("Staff number is required."));
        }

        LocalDateTime now = LocalDateTime.now();
        Worker worker = toEntity(request, null, now, now);
        return ensureStaffNumberAvailable(request.getStaffNumber(), null)
                .then(workerRepository.save(worker))
                .map(this::toResponse);
    }

    public Mono<WorkerResponse> update(Long id, WorkerRequest request) {
        if (request.getStaffNumber() == null || request.getStaffNumber().isBlank()) {
            return Mono.error(new IllegalArgumentException("Staff number is required."));
        }

        return workerRepository.findById(id)
                .flatMap(existing -> ensureStaffNumberAvailable(request.getStaffNumber(), id).then(Mono.defer(() -> {
                    Worker worker = toEntity(
                            request,
                            id,
                            existing.getCreatedAt() == null ? LocalDateTime.now() : existing.getCreatedAt(),
                            LocalDateTime.now()
                    );
                    return workerRepository.save(worker);
                })))
                .map(this::toResponse);
    }

    public Mono<Void> delete(Long id) {
        return workerRepository.deleteById(id);
    }

    public Flux<WorkerResponse> search(String name) {
        return workerRepository.findByNameContainingIgnoreCase(name).map(this::toResponse);
    }

    public Mono<List<WorkerResponse>> importWorkers(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(this::toBytes)
                .flatMap(bytes -> Mono.fromCallable(() -> parseWorkerRows(bytes))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::upsertImportedWorker)
                .map(this::toResponse)
                .collectList();
    }

    private Mono<Worker> upsertImportedWorker(ImportedWorker row) {
        LocalDateTime now = LocalDateTime.now();
        return workerRepository.findByStaffNumber(row.staffNumber())
                .flatMap(existing -> {
                    existing.setName(row.name());
                    existing.setUpdatedAt(now);
                    return workerRepository.save(existing);
                })
                .switchIfEmpty(workerRepository.save(Worker.builder()
                        .staffNumber(row.staffNumber())
                        .name(row.name())
                        .email("")
                        .phone("")
                        .area("")
                        .homeAddress(null)
                        .createdAt(now)
                        .updatedAt(now)
                        .build()));
    }

    private byte[] toBytes(DataBuffer dataBuffer) {
        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);
        DataBufferUtils.release(dataBuffer);
        return bytes;
    }

    private List<ImportedWorker> parseWorkerRows(byte[] bytes) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("The Excel file is empty.");
            }

            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = findWorkerColumns(header, formatter);
            List<ImportedWorker> workers = new ArrayList<>();

            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }

                String staffNumber = getText(row, columns.get("staff number"), formatter);
                String name = getText(row, columns.get("name"), formatter);
                if (staffNumber.isBlank() || name.isBlank()) {
                    throw new IllegalArgumentException("Row " + (i + 1) + " must include Staff Number and Name.");
                }
                workers.add(new ImportedWorker(staffNumber, name));
            }

            if (workers.isEmpty()) {
                throw new IllegalArgumentException("No worker rows were found in the Excel file.");
            }

            return workers;
        }
    }

    private Map<String, Integer> findWorkerColumns(Row header, DataFormatter formatter) {
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : header) {
            String value = formatter.formatCellValue(cell).trim().toLowerCase(Locale.ENGLISH);
            if (!value.isBlank()) {
                columns.put(value, cell.getColumnIndex());
            }
        }

        for (String required : List.of("staff number", "name")) {
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

    private Worker toEntity(WorkerRequest request, Long id, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return Worker.builder()
                .id(id)
                .staffNumber(request.getStaffNumber().trim())
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .area(request.getArea())
                .homeAddress(request.getHomeAddress())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private WorkerResponse toResponse(Worker worker) {
        return WorkerResponse.builder()
                .id(worker.getId())
                .staffNumber(worker.getStaffNumber())
                .name(worker.getName())
                .email(worker.getEmail())
                .phone(worker.getPhone())
                .area(worker.getArea())
                .homeAddress(worker.getHomeAddress())
                .createdAt(worker.getCreatedAt())
                .updatedAt(worker.getUpdatedAt())
                .build();
    }

    private Mono<Void> ensureStaffNumberAvailable(String staffNumber, Long currentWorkerId) {
        return workerRepository.findByStaffNumber(staffNumber.trim())
                .filter(worker -> currentWorkerId == null || !currentWorkerId.equals(worker.getId()))
                .flatMap(worker -> Mono.error(new IllegalArgumentException("Staff number already exists: " + staffNumber)))
                .then();
    }

    private record ImportedWorker(String staffNumber, String name) {
    }
}
