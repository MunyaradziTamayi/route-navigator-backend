package com.routenav.controller;

import com.routenav.dto.AuthRequest;
import com.routenav.dto.ErrorResponse;
import com.routenav.dto.RegisterRequest;
import com.routenav.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Mono<ResponseEntity<Object>> register(@RequestBody RegisterRequest request) {
        return authService.register(request)
                .map(response -> ResponseEntity.ok((Object) response))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Object>> login(@RequestBody AuthRequest request) {
        return authService.login(request)
                .map(response -> ResponseEntity.ok((Object) response))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest()
                        .body(ErrorResponse.builder().message(e.getMessage()).build())));
    }
}
