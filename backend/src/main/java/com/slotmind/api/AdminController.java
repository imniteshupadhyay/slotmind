package com.slotmind.api;

import com.slotmind.agent.AgentActivityLog;
import com.slotmind.ai.LlmProperties;
import com.slotmind.engine.SchedulingEngine;
import com.slotmind.service.SeedService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class AdminController {

    public record StrategyRequest(@NotBlank String strategy) {}

    private final SchedulingEngine engine;
    private final SeedService seed;
    private final AgentActivityLog activity;
    private final LlmProperties llm;

    public AdminController(SchedulingEngine engine, SeedService seed, AgentActivityLog activity, LlmProperties llm) {
        this.engine = engine;
        this.seed = seed;
        this.activity = activity;
        this.llm = llm;
    }

    @GetMapping("/config/strategy")
    public Map<String, Object> strategy() {
        return Map.of("active", engine.getActive(), "available", engine.available(),
                "llmConfigured", llm.configured(), "llmModel", llm.model());
    }

    /** Runtime switch; affects both HTTP suggestions and the async agent immediately. */
    @PutMapping("/config/strategy")
    public Map<String, Object> setStrategy(@Valid @RequestBody StrategyRequest req) {
        engine.setActive(req.strategy());
        return strategy();
    }

    @GetMapping("/agent/activity")
    public List<AgentActivityLog.Entry> activity() {
        return activity.recent();
    }

    @PostMapping("/admin/reset")
    public Map<String, String> reset() {
        seed.reset();
        return Map.of("status", "reseeded");
    }
}
