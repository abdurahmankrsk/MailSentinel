package com.mailsentinel.ai;

public interface AiProvider {
    AiAnalysisResult analyze(AiAnalysisRequest request) throws AiProviderException;

    String providerName();
}
