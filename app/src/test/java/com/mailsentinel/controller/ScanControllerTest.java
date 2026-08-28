package com.mailsentinel.controller;

import com.mailsentinel.dto.ScanResponse;
import com.mailsentinel.service.ScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScanController.class)
class ScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScoringService scoringService;

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
        when(scoringService.runScan(anyString(), anyString()))
            .thenReturn(new ScanResponse(0, List.of()));

        mockMvc.perform(post("/api/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"url\",\"content\":\"https://paypal.com\"}"))
            .andExpect(status().isOk());
    }
}
