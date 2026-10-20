package com.slotmind.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code slotmind.llm.*}; the key comes from the LLM_API_KEY environment variable. */
@ConfigurationProperties(prefix = "slotmind.llm")
public record LlmProperties(String baseUrl, String apiKey, String model, String productHeader, int timeoutSeconds) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
