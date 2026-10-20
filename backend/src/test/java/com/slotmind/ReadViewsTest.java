package com.slotmind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.service.SeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The provider and slot endpoints return read views: the fields the UI reads, and no JPA internals. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-slotmind.db?busy_timeout=10000",
        "slotmind.strategy.active=rule",
        "slotmind.llm.api-key="
})
@AutoConfigureMockMvc
class ReadViewsTest {

    private static final List<String> PROVIDER_FIELDS = List.of("id", "name", "specialty", "status", "bookable");
    private static final List<String> SLOT_FIELDS = List.of("id", "providerId", "providerName", "startTime",
            "durationMinutes", "status", "appointmentId", "patientName");

    @Autowired MockMvc mvc;
    @Autowired SeedService seed;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        seed.reset();
    }

    @Test
    void providerListHasTheFieldsTheUiReads() throws Exception {
        JsonNode list = call(get("/providers"), 200);
        assertThat(list).isNotEmpty();
        JsonNode first = list.get(0);
        assertThat(fields(first)).containsExactlyInAnyOrderElementsOf(PROVIDER_FIELDS);
        assertThat(first.get("id").asText()).isEqualTo("PRV-001");
        assertThat(first.get("name").asText()).isEqualTo("Dr. Sarah Chen");
        assertThat(first.get("specialty").asText()).isEqualTo("GENERAL");
        assertThat(first.get("status").asText()).isEqualTo("BUSY");
    }

    @Test
    void providerStatusChangeReturnsTheProviderView() throws Exception {
        JsonNode p = call(patch("/providers/PRV-001/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"AVAILABLE\"}"), 202);
        assertThat(fields(p)).containsExactlyInAnyOrderElementsOf(PROVIDER_FIELDS);
        assertThat(p.get("status").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    void slotListHasTheFieldsTheUiReads() throws Exception {
        JsonNode list = call(get("/slots"), 200);
        assertThat(list).isNotEmpty();
        list.forEach(s -> assertThat(fields(s)).containsExactlyInAnyOrderElementsOf(SLOT_FIELDS));
    }

    @Test
    void creatingASlotReturnsASlotViewWithoutTheLockVersion() throws Exception {
        JsonNode s = call(post("/slots").contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerId\":\"PRV-004\",\"startTime\":\"2030-01-01T09:00:00\",\"durationMinutes\":30}"), 201);
        assertThat(fields(s)).containsExactlyInAnyOrderElementsOf(SLOT_FIELDS);
        assertThat(s.get("providerName").asText()).isEqualTo("Dr. Rahul Verma");
        assertThat(s.get("status").asText()).isEqualTo("OPEN");
        assertThat(s.get("durationMinutes").asInt()).isEqualTo(30);
    }

    @Test
    void slotStatusChangeReturnsASlotViewWithoutTheLockVersion() throws Exception {
        JsonNode s = call(patch("/slots/SLT-012/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\"}"), 202);
        assertThat(fields(s)).containsExactlyInAnyOrderElementsOf(SLOT_FIELDS);
        assertThat(s.get("id").asText()).isEqualTo("SLT-012");
        assertThat(s.get("providerId").asText()).isEqualTo("PRV-004");
    }

    @Test
    void aSuggestionFromTheActiveStrategyIsNotMarkedAsFallback() throws Exception {
        JsonNode s = call(post("/appointments/APT-001/suggest"), 200);
        assertThat(s.get("strategy").asText()).isEqualTo("rule");
        assertThat(s.get("fallback").asBoolean()).isFalse();
        assertThat(s.get("fallbackReason").isNull()).isTrue();
    }

    @Test
    void aSuggestionMadeAfterTheAiFailsIsMarkedAsFallbackWithTheReason() throws Exception {
        setStrategy("ai"); // no API key in tests, so the AI strategy fails and the engine falls back
        try {
            JsonNode s = call(post("/appointments/APT-001/suggest"), 200);
            assertThat(s.get("fallback").asBoolean()).isTrue();
            assertThat(s.get("fallbackReason").asText()).isEqualTo("ai failed: LLM_API_KEY is not configured");

            JsonNode listed = call(get("/suggestions"), 200).get(0);
            assertThat(listed.get("id").asText()).isEqualTo(s.get("id").asText());
            assertThat(listed.get("fallback").asBoolean()).isTrue();
        } finally {
            setStrategy("rule");
        }
    }

    private void setStrategy(String name) throws Exception {
        call(put("/config/strategy").contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\":\"" + name + "\"}"), 200);
    }

    private JsonNode call(RequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
