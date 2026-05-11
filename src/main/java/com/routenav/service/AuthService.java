package com.routenav.service;

import com.routenav.dto.AuthRequest;
import com.routenav.dto.AuthResponse;
import com.routenav.dto.RegisterRequest;
import com.routenav.model.AppUser;
import com.routenav.repository.AppUserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository appUserRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public Mono<AuthResponse> register(RegisterRequest request) {
        String name = clean(request.getName());
        String email = clean(request.getEmail()).toLowerCase();
        String password = request.getPassword() == null ? "" : request.getPassword();

        if (name.isBlank() || email.isBlank() || password.length() < 6) {
            return Mono.error(new IllegalArgumentException("Name, email, and a password of at least 6 characters are required."));
        }

        return appUserRepository.findByEmail(email)
                .flatMap(existing -> Mono.<AppUser>error(new IllegalArgumentException("An account with this email already exists.")))
                .switchIfEmpty(appUserRepository.save(AppUser.builder()
                        .name(name)
                        .email(email)
                        .passwordHash(passwordEncoder.encode(password))
                        .createdAt(LocalDateTime.now())
                        .build()))
                .map(this::toResponse);
    }

    public Mono<AuthResponse> login(AuthRequest request) {
        String email = clean(request.getEmail()).toLowerCase();
        String password = request.getPassword() == null ? "" : request.getPassword();

        return appUserRepository.findByEmail(email)
                .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid email or password.")))
                .map(this::toResponse);
    }

    private AuthResponse toResponse(AppUser user) {
        return AuthResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
