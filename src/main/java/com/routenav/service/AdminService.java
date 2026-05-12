package com.routenav.service;

import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AdminService {

    private static final List<String> RESET_STATEMENTS = List.of(
            "DELETE FROM worker_mileage_report_rows",
            "DELETE FROM worker_mileage_visits",
            "DELETE FROM worker_mileage_reports",
            "DELETE FROM worker_mileage_uploads",
            "DELETE FROM visits",
            "DELETE FROM workers",
            "DELETE FROM routes"
    );

    private final DatabaseClient databaseClient;

    public AdminService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<Void> resetDatabase() {
        return Flux.fromIterable(RESET_STATEMENTS)
                .concatMap(statement -> databaseClient.sql(statement).fetch().rowsUpdated())
                .then();
    }
}
