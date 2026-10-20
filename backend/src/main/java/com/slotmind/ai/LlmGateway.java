package com.slotmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Thin client for the OpenAI-compatible LiteLLM gateway: blocking and streaming chat completions. */
@Component
public class LlmGateway {

    public record Message(String role, String content) {}

    private final LlmProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public LlmGateway(LlmProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String chat(List<Message> messages) {
        HttpResponse<String> res = send(messages, false, HttpResponse.BodyHandlers.ofString());
        try {
            JsonNode body = json.readTree(res.body());
            return body.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            throw new LlmException("Unparseable LLM response", e);
        }
    }

    /** Streams content deltas to {@code onDelta} and returns the full text. */
    public String stream(List<Message> messages, Consumer<String> onDelta) {
        HttpResponse<Stream<String>> res = send(messages, true, HttpResponse.BodyHandlers.ofLines());
        StringBuilder full = new StringBuilder();
        try (Stream<String> lines = res.body()) {
            lines.forEach(line -> {
                if (!line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) return;
                try {
                    String delta = json.readTree(data).path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        full.append(delta);
                        onDelta.accept(delta);
                    }
                } catch (IOException ignored) {
                    // keep-alive or partial frame; skip
                }
            });
        }
        return full.toString();
    }

    private <T> HttpResponse<T> send(List<Message> messages, boolean stream, HttpResponse.BodyHandler<T> handler) {
        if (!props.configured()) {
            throw new LlmException("LLM_API_KEY is not configured");
        }
        ObjectNode req = json.createObjectNode();
        req.put("model", props.model());
        req.put("temperature", 0.2);
        req.put("stream", stream);
        req.set("messages", json.valueToTree(messages));
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("Authorization", "Bearer " + props.apiKey())
                .header("Content-Type", "application/json")
                .header("product", props.productHeader())
                .POST(HttpRequest.BodyPublishers.ofString(req.toString()))
                .build();
        try {
            HttpResponse<T> res = http.send(request, handler);
            if (res.statusCode() / 100 != 2) {
                throw new LlmException("LLM gateway returned HTTP " + res.statusCode());
            }
            return res;
        } catch (IOException e) {
            throw new LlmException("LLM gateway unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while calling LLM", e);
        }
    }
}
