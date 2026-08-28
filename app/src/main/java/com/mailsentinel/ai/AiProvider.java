package com.mailsentinel.ai;

public interface AiProvider {
    /**
     * @param overrideApiKey when non-null, used for this call instead of the
     *                       server's own configured key (a user's bring-your-own-key)
     */
    AiAnalysisResult analyze(AiAnalysisRequest request, String overrideApiKey) throws AiProviderException;

    String providerName();
}
