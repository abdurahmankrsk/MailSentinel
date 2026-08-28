package com.mailsentinel.ai;

import com.mailsentinel.auth.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/account/** requires authentication (see SecurityConfig), so currentUser is
 * guaranteed non-null in every handler here -- an unauthenticated request never
 * reaches this controller.
 */
@RestController
@RequestMapping("/api/account/ai-key")
public class AiKeyController {

    private final AiKeyService aiKeyService;

    public AiKeyController(AiKeyService aiKeyService) {
        this.aiKeyService = aiKeyService;
    }

    /**
     * Public (see SecurityConfig): lets the frontend show or hide the whole
     * bring-your-own-key section for a visitor who isn't signed in yet, the same
     * way AuthController#config lets it show or hide the Google button.
     */
    @GetMapping("/config")
    public AiKeyStatusResponse config() {
        return new AiKeyStatusResponse(aiKeyService.isFeatureEnabled(), null, null);
    }

    @GetMapping
    public AiKeyStatusResponse status(@AuthenticationPrincipal User currentUser) {
        boolean enabled = aiKeyService.isFeatureEnabled();
        return aiKeyService.status(currentUser.getId())
                .map(s -> new AiKeyStatusResponse(enabled, s.label(), s.last4()))
                .orElseGet(() -> new AiKeyStatusResponse(enabled, null, null));
    }

    @PostMapping
    public AiKeyStatusResponse save(@AuthenticationPrincipal User currentUser, @RequestBody SaveAiKeyRequest request) {
        AiKeyStatus status = aiKeyService.save(
                currentUser.getId(),
                request == null ? null : request.label(),
                request == null ? null : request.baseUrl(),
                request == null ? null : request.model(),
                request == null ? null : request.key());
        return new AiKeyStatusResponse(true, status.label(), status.last4());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser) {
        aiKeyService.delete(currentUser.getId());
        return ResponseEntity.noContent().build();
    }
}
