package com.routenav.controller;

import com.routenav.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping("/reset")
    public Mono<ResponseEntity<Void>> resetDatabase() {
        return adminService.resetDatabase()
                .thenReturn(ResponseEntity.noContent().build());
    }
}
