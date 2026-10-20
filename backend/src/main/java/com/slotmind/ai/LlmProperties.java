package com.slotmind.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code slotmind.llm.*}. The key normally comes from the git-ignored
 * {@code application-local.properties}; when that file doesn't set it, {@code application.properties}
 * falls back to the {@code LLM_API_KEY} environment variable.
 */
@ConfigurationProperties(prefix = "slotmind.llm")
public record LlmProperties(String baseUrl, String apiKey, String model, String productHeader, int timeoutSeconds) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
