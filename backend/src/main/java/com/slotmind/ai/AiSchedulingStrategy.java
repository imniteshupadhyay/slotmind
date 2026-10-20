package com.slotmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.engine.Recommendation;
import com.slotmind.engine.SchedulingContext;
import com.slotmind.engine.SchedulingStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM-backed strategy. Output is parsed and validated here (slot must be an OPEN candidate); anything
 * wrong throws, and the {@link com.slotmind.engine.SchedulingEngine} falls back to the rule-based strategy.
 */
@Component
public class AiSchedulingStrategy implements SchedulingStrategy {

    public static final String NAME = "ai";

    private final LlmGateway llm;
    private final PromptFactory prompts;
    private final ObjectMapper json;

    public AiSchedulingStrategy(LlmGateway llm, PromptFactory prompts, ObjectMapper json) {
        this.llm = llm;
        this.prompts = prompts;
        this.json = json;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Recommendation> recommend(SchedulingContext ctx) {
        if (ctx.candidateSlots().isEmpty()) return List.of();
        return List.of(parse(llm.chat(prompts.build(ctx)), ctx));
    }

    @Override
    public List<Recommendation> recommend(SchedulingContext ctx, Consumer<String> progress) {
        if (ctx.candidateSlots().isEmpty()) return List.of();
        return List.of(parse(llm.stream(prompts.build(ctx), progress), ctx));
    }

    /** Extracts and validates the JSON answer; tolerant of think-tags and markdown fences. */
    public Recommendation parse(String raw, SchedulingContext ctx) {
        String text = raw == null ? "" : raw.replaceAll("(?s)<think>.*?</think>", "");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmException("LLM response contained no JSON object");
        }
        JsonNode node;
        try {
            node = json.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            throw new LlmException("LLM returned malformed JSON", e);
        }
        String slotId = node.path("slotId").asText(null);
        if (slotId == null || ctx.candidate(slotId) == null) {
            throw new LlmException("LLM picked slot '" + slotId + "' which is not an open candidate");
        }
        String alt = node.path("alternateProviderId").isTextual() ? node.path("alternateProviderId").asText() : null;
        if (alt != null && !alt.isBlank() && !ctx.providers().containsKey(alt)) {
            throw new LlmException("LLM referenced unknown provider '" + alt + "'");
        }
        double confidence = node.path("confidence").asDouble(0.5);
        return new Recommendation(slotId, alt, confidence, node.path("reasoning").asText(""));
    }
}
