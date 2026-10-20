# Return read views for Provider and Slot; fix the stale LlmProperties Javadoc

Type: task
Status: resolved
Blocked by: none

## Question

Nothing here needs deciding. It aligns the code with the README's layout note ("api/ … read views").

- `ResourceControllers.java:149,156,185,192` return the `Provider` and `Slot` JPA entities directly. Add `Views` projections with **the same JSON field names**, so `frontend/src` keeps working unchanged. Check what `frontend/src/api.js` and `components.jsx` read before changing anything.
- `LlmProperties.java:6`: the Javadoc still says the key comes from `LLM_API_KEY`. Since commit 074de36 the primary path is `application-local.properties`, with the env var as an override.

Done when `mvn -q test` passes and the frontend renders the same data. (AFK)

## Answer

Implemented in `98d1731` (merged in `89b94f3`).

- **Read views.** `Views.ProviderView` was added, and the provider/slot endpoints in `ResourceControllers` return views. `GET /providers` and `GET /slots` keep their JSON shape exactly.
- **Slot write responses.** `POST /slots` and `PATCH /slots/{id}/status` now return the same `SlotView` as `GET /slots`. That drops the leaked `version` and adds the view fields. This is a deliberate deviation from "same JSON field names": the frontend ignores these responses, and the new shape matches the list.
- **Javadoc.** The `LlmProperties` Javadoc now names `application-local.properties` as the primary key source, with `LLM_API_KEY` as the fallback.
- **Test.** `ReadViewsTest` (MockMvc).
