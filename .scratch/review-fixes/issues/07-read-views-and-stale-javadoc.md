# Return read views for Provider and Slot; fix the stale LlmProperties Javadoc

Type: task
Status: claimed
Blocked by: none

## Question

Nothing here needs deciding. It aligns the code with the README's layout note ("api/ … read views").

- `ResourceControllers.java:149,156,185,192` return the `Provider` and `Slot` JPA entities directly. Add `Views` projections with **the same JSON field names**, so `frontend/src` keeps working unchanged. Check what `frontend/src/api.js` and `components.jsx` read before changing anything.
- `LlmProperties.java:6`: the Javadoc still says the key comes from `LLM_API_KEY`. Since commit 074de36 the primary path is `application-local.properties`, with the env var as an override.

Done when `mvn -q test` passes and the frontend renders the same data. (AFK)
