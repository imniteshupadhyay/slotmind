# SlotMind — AI Appointment Re-Scheduling Engine

When a provider calls in sick or a slot is cancelled, SlotMind finds every affected appointment, asks a
pluggable scheduling engine (LLM or rule-based) for the best replacement, and queues a patient-friendly
suggestion for the front desk to confirm. Accepting rebooks atomically, with no double booking.

- **Backend:** Spring Boot 3.4 · Java 17+ · JPA · SQLite
- **Frontend:** React 18 · Vite
- **AI:** Zycus LiteLLM gateway (`qwen-cursor`, OpenAI-compatible), with automatic rule-based fallback
- **Design decisions:** see [ADR.md](ADR.md)

## Run it

### 1. Backend (port 8080)

```bash
cd backend
# never commit the key — pass it via environment
export LLM_API_KEY=<your-litellm-key>        # PowerShell: $env:LLM_API_KEY="<key>"
mvn spring-boot:run
```

It seeds the Addendum A data (plus extra open slots) into `backend/slotmind.db` on first start.
Without a key, or if the gateway is unreachable (it needs the Zycus network/VPN), the AI strategy
falls back to rule-based automatically, and the UI labels those suggestions.

| Env var | Default |
|---|---|
| `LLM_API_KEY` | *(empty → AI falls back to rules)* |
| `LLM_BASE_URL` | `https://litellm-qc.zycus.net` |
| `LLM_MODEL` | `qwen-cursor` |
| `LLM_PRODUCT` | `PC1` (sent as `product` header) |
| `SLOTMIND_STRATEGY` | `ai` (`ai` \| `rule`) |

### 2. Frontend (port 5173)

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. The Vite dev server proxies `/api/*` to the backend.

### Tests

```bash
cd backend && mvn test
```

Covers rule ordering, LLM-output validation, AI → rule fallback, and **two concurrent accepts on the
same slot → exactly one wins**.

## Demo script (5 min)

1. **Reset demo** (top bar). Dr. Sarah Chen has John Martinez and Emily Watson booked.
2. In **Providers**, set **Dr. Sarah Chen → Offline**. The PATCH returns 202 immediately.
3. Within a few seconds the **Reschedule queue** shows both patients with a red *Provider offline*
   badge. Each gets a *different* opening with Dr. Rahul Verma (same specialty), a confidence bar,
   and reasoning staff can read aloud. **Agent activity** shows the loop's steps.
4. **Accept & rebook** John. In the **Day view**, his new slot turns booked and his old one is freed.
   The appointment becomes `RESCHEDULED`.
5. Cancel one of a still-working doctor's booked slots (✕ on the chip in **Day view**). A *Slot cancelled*
   suggestion keeps the patient with the same doctor where possible.
6. In **All appointments**, click **✦ Stream** to watch the AI reasoning stream live (SSE), then accept
   it from the modal.
7. Flip the strategy toggle **AI ↔ Rule-based** at runtime.

## API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/appointments` | book `{patientName, appointmentType, providerId, slotId}` |
| `GET` | `/appointments?status=` | includes inline pending suggestion |
| `PATCH` | `/appointments/{id}/status` | `{status: COMPLETED}` |
| `POST` | `/appointments/{id}/suggest` | on-demand suggestion (trigger `INITIAL`) |
| `POST` | `/appointments/{id}/suggest/stream` | **SSE**: `token` events, then `suggestion` |
| `GET` | `/providers` | |
| `PATCH` | `/providers/{id}/status` | `OFFLINE` fires the agentic loop (202) |
| `GET` / `POST` | `/slots` | list (filter `providerId`, `status`) / create open slot |
| `PATCH` | `/slots/{id}/status` | `CANCELLED` fires the agentic loop (202) |
| `GET` | `/suggestions?status=` | |
| `PATCH` | `/suggestions/{id}` | `{status: ACCEPTED \| REJECTED}`; accept is atomic, 409 on conflict |
| `GET` / `PUT` | `/config/strategy` | runtime switch `{strategy: "ai" \| "rule"}` |
| `GET` | `/agent/activity` | agent feed |
| `POST` | `/admin/reset` | re-seed demo data |

Quick check:

```bash
curl -X PATCH localhost:8080/providers/PRV-001/status -H 'Content-Type: application/json' -d '{"status":"OFFLINE"}'
curl localhost:8080/suggestions?status=PENDING
```

## Layout

```
backend/src/main/java/com/slotmind/
  domain/   entities + state machines (Appointment, Provider, Slot, RescheduleSuggestion)
  engine/   SchedulingStrategy contract, RuleBasedStrategy, SchedulingEngine (registry, validation, fallback)
  ai/       LlmGateway (blocking + streaming), PromptFactory (two prompts), AiSchedulingStrategy
  agent/    domain events, RescheduleAgent (async loop), activity feed
  service/  BookingService (commands, atomic accept), ReschedulingService (proposals), SeedService
  api/      REST controllers, read views, error mapping
frontend/src/  App.jsx, components.jsx, api.js (incl. SSE reader)
```
