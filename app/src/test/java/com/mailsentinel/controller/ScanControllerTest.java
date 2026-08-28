package com.mailsentinel.controller;

import com.mailsentinel.ai.AiAnalysisService;
import com.mailsentinel.dto.ScanResponse;
import com.mailsentinel.service.ScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// This slice never loads the real SecurityConfig (a @Configuration class isn't part
// of @WebMvcTest's narrow scan), so without addFilters=false Spring Boot falls back
// to its own default deny-by-default security instead of the app's real, permitAll
// rule for /api/scan. Real end-to-end security behavior (including that /api/scan
// stays reachable with no token) is covered separately by auth.AuthControllerTest,
// which boots the full application context.
@WebMvcTest(ScanController.class)
@AutoConfigureMockMvc(addFilters = false)
class ScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScoringService scoringService;

    @MockBean
    private AiAnalysisService aiAnalysisService;

    @Test
    void rejectsUnknownScanTypeWithBadRequest() throws Exception {
        // Previously silently treated as a URL scan instead of being rejected.
        mockMvc.perform(post("/api/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"banana\",\"content\":\"https://paypal.com\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankContentWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"url\",\"content\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsValidUrlScanRequest() throws Exception {
        ScanResponse deterministic = new ScanResponse(0, List.of(), null);
        when(scoringService.runScan(anyString(), anyString())).thenReturn(deterministic);
        // This slice tests controller-level validation only, not AI routing (that's
        // AiAnalysisServiceTest's job) -- stub it to pass the deterministic result through
        // unchanged, exactly as it does for a real anonymous/FREE caller.
        when(aiAnalysisService.analyze(any(), anyString(), anyString(), any(), any())).thenReturn(deterministic);

        mockMvc.perform(post("/api/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"url\",\"content\":\"https://paypal.com\"}"))
            .andExpect(status().isOk());
    }
}
