package com.mailsentinel.controller;

import com.mailsentinel.ai.AiAnalysisService;
import com.mailsentinel.auth.User;
import com.mailsentinel.dto.ScanRequest;
import com.mailsentinel.dto.ScanResponse;
import com.mailsentinel.service.ScoringService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for scanning emails and URLs.
 *
 * CORS is open by design: API is stateless, no session credentials,
 * and ready for future browser extension callers.
 *
 * No token required (SecurityConfig permits this path for everyone) -- an anonymous
 * or FREE-plan caller gets the deterministic result only; AiAnalysisService decides
 * per-caller whether anything more happens.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class ScanController {

    private final ScoringService scoringService;
    private final AiAnalysisService aiAnalysisService;

    public ScanController(ScoringService scoringService, AiAnalysisService aiAnalysisService) {
        this.scoringService = scoringService;
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostMapping("/scan")
    public ScanResponse scan(
            @RequestBody ScanRequest request,
            @AuthenticationPrincipal User currentUser,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (request == null || request.type() == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing type or content");
        }
        if (!"email".equals(request.type()) && !"url".equals(request.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be 'email' or 'url'");
        }
        ScanResponse deterministic = scoringService.runScan(request.type(), request.content());
        return aiAnalysisService.analyze(currentUser, request.type(), request.content(), deterministic, idempotencyKey);
    }
}
