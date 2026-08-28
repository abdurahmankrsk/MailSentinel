package com.lookalike.controller;

import com.lookalike.dto.ScanRequest;
import com.lookalike.dto.ScanResponse;
import com.lookalike.service.ScoringService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for scanning emails and URLs.
 *
 * CORS is open by design: API is stateless, no session credentials,
 * and ready for future browser extension callers.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class ScanController {

    private final ScoringService scoringService;

    public ScanController(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @PostMapping("/scan")
    public ScanResponse scan(@RequestBody ScanRequest request) {
        if (request == null || request.type() == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing type or content");
        }
        return scoringService.runScan(request.type(), request.content());
    }
}
