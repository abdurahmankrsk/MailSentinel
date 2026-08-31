package com.mailsentinel.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiKeyServiceTest {

    private static final Long USER_ID = 42L;
    private static final String BASE_URL = "https://api.groq.com/openai/v1";
    private static final String MODEL = "llama-3.3-70b-versatile";
    private static final String PUBLIC_ADDRESS = "93.184.216.34";

    private UserAiKeyRepository repository;
    private AiKeyCipher cipher;
    private AiProviderFactory aiProviderFactory;
    private AiProvider probeProvider;
    private AiKeyService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserAiKeyRepository.class);
        cipher = mock(AiKeyCipher.class);
        aiProviderFactory = mock(AiProviderFactory.class);
        probeProvider = mock(AiProvider.class);
        when(cipher.isConfigured()).thenReturn(true);
        when(aiProviderFactory.create(any(), any(), any())).thenReturn(probeProvider);
        service = new AiKeyService(repository, cipher, aiProviderFactory, guardResolving(PUBLIC_ADDRESS));
    }

    // A stubbed resolver, not the production one: these tests are about AiKeyService's
    // handling of the guard's verdict, and a real lookup of api.groq.com would make
    // every one of them depend on DNS. OutboundUrlGuardTest covers the verdict itself.
    private static OutboundUrlGuard guardResolving(String address) {
        return new OutboundUrlGuard(false, host -> new InetAddress[]{InetAddress.getByName(address)});
    }

    @Test
    void saveRejectsWhenFeatureNotConfigured() {
        when(cipher.isConfigured()).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(USER_ID, "Groq", BASE_URL, MODEL, "a-valid-looking-key"));

        assertEquals(503, ex.getStatusCode().value());
    }

    @Test
    void saveRejectsABaseUrlThatIsNotHttp() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(USER_ID, "Groq", "ftp://not-http.example.com", MODEL, "a-valid-looking-key"));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void saveRejectsAPlaintextHttpBaseUrl() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(USER_ID, "Groq", "http://api.groq.com/openai/v1", MODEL, "a-valid-looking-key"));

        assertEquals(400, ex.getStatusCode().value());
        verify(aiProviderFactory, never()).create(any(), any(), any());
    }

    @Test
    void saveRefusesAnEndpointResolvingToAPrivateAddressWithoutCallingIt() {
        service = new AiKeyService(repository, cipher, aiProviderFactory, guardResolving("127.0.0.1"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(USER_ID, "x", "https://rebind.example.com/v1", MODEL, "a-valid-looking-key"));

        assertEquals(400, ex.getStatusCode().value());
        // The point of the guard: the outbound request never happens at all.
        verify(aiProviderFactory, never()).create(any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void saveDoesNotEchoTheUpstreamErrorBackToTheCaller() throws Exception {
        when(probeProvider.analyze(any(), isNull()))
                .thenThrow(new AiProviderException("I/O error on POST request for \"http://127.0.0.1:1/api\""));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(USER_ID, "Groq", BASE_URL, MODEL, "a-bad-but-long-enough-key"));

        // Reflecting the transport error told an attacker an open internal port from a
        // closed one; the reply has to be the same either way.
        assertEquals("That API key or endpoint could not be reached", ex.getReason());
    }

    @Test
    void saveRejectsABlankModel() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(USER_ID, "Groq", BASE_URL, "  ", "a-valid-looking-key"));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void saveRejectsBlankOrTooShortKey() {
        assertThrows(ResponseStatusException.class, () -> service.save(USER_ID, "Groq", BASE_URL, MODEL, ""));
        assertThrows(ResponseStatusException.class, () -> service.save(USER_ID, "Groq", BASE_URL, MODEL, "short"));
    }

    @Test
    void saveRejectsAKeyTheEndpointRejects() throws Exception {
        when(probeProvider.analyze(any(), isNull())).thenThrow(new AiProviderException("401"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(USER_ID, "Groq", BASE_URL, MODEL, "a-bad-but-long-enough-key"));

        assertEquals(400, ex.getStatusCode().value());
        verify(repository, never()).save(any());
    }

    @Test
    void saveBuildsTheProbeClientFromTheSubmittedEndpoint() throws Exception {
        when(probeProvider.analyze(any(), isNull())).thenReturn(new AiAnalysisResult("ok", List.of()));
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        service.save(USER_ID, "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "a-valid-looking-key");

        verify(aiProviderFactory).create(eq("https://api.openai.com/v1"), eq("gpt-4o-mini"), eq("a-valid-looking-key"));
    }

    @Test
    void saveEncryptsAndPersistsOnSuccess() throws Exception {
        when(probeProvider.analyze(any(), isNull())).thenReturn(new AiAnalysisResult("ok", List.of()));
        when(cipher.encrypt("a-valid-looking-key")).thenReturn("ciphertext-abc");
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        AiKeyStatus status = service.save(USER_ID, "Groq", BASE_URL, MODEL, "a-valid-looking-key");

        assertEquals("Groq", status.label());
        assertEquals("-key", status.last4(), "last4 must be the trailing 4 characters of the raw key");
        verify(repository).save(argThat(k ->
                k.getUserId().equals(USER_ID)
                        && k.getBaseUrl().equals(BASE_URL)
                        && k.getModel().equals(MODEL)
                        && k.getKeyCiphertext().equals("ciphertext-abc")));
    }

    @Test
    void saveDefaultsTheLabelWhenBlank() throws Exception {
        when(probeProvider.analyze(any(), isNull())).thenReturn(new AiAnalysisResult("ok", List.of()));
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        AiKeyStatus status = service.save(USER_ID, "  ", BASE_URL, MODEL, "a-valid-looking-key");

        assertEquals("AI", status.label());
    }

    @Test
    void saveReplacesAnExistingKeyRatherThanAccumulating() throws Exception {
        when(probeProvider.analyze(any(), isNull())).thenReturn(new AiAnalysisResult("ok", List.of()));
        when(cipher.encrypt(any())).thenReturn("new-ciphertext");
        UserAiKey existing = new UserAiKey(USER_ID, "Groq", BASE_URL, MODEL, "old-ciphertext", "old4");
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        service.save(USER_ID, "Groq", BASE_URL, MODEL, "a-new-valid-key");

        verify(repository, times(1)).delete(existing);
        verify(repository, times(1)).save(any());
    }

    @Test
    void statusReturnsEmptyWhenNoKeyIsSaved() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertTrue(service.status(USER_ID).isEmpty());
    }

    @Test
    void statusReturnsLabelAndLast4WhenSaved() {
        when(repository.findByUserId(USER_ID)).thenReturn(
                Optional.of(new UserAiKey(USER_ID, "Groq", BASE_URL, MODEL, "ciphertext", "wxyz")));

        Optional<AiKeyStatus> status = service.status(USER_ID);

        assertEquals("Groq", status.orElseThrow().label());
        assertEquals("wxyz", status.orElseThrow().last4());
    }

    @Test
    void activeKeyForDecryptsTheStoredCiphertextAndCarriesTheEndpoint() {
        when(repository.findByUserId(USER_ID)).thenReturn(
                Optional.of(new UserAiKey(USER_ID, "Groq", BASE_URL, MODEL, "ciphertext", "wxyz")));
        when(cipher.decrypt("ciphertext")).thenReturn("the-raw-key");

        ActiveAiKey active = service.activeKeyFor(USER_ID).orElseThrow();

        assertEquals(BASE_URL, active.baseUrl());
        assertEquals(MODEL, active.model());
        assertEquals("the-raw-key", active.apiKey());
    }

    @Test
    void deleteDelegatesToTheRepository() {
        service.delete(USER_ID);

        verify(repository, times(1)).deleteByUserId(USER_ID);
    }
}
