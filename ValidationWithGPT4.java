package GPT;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ValidationWithGPT4 {

    // ─── Configuration ────────────────────────────────────────────────────────

    private static final Logger LOGGER = Logger.getLogger(ValidationWithGPT4.class.getName());

    private static final String API_KEY              = "";
    private static final String API_URL              = "https://api.openai.com/v1/responses";
    private static final int    SIMILARITY_THRESHOLD = 75;
    private static final int    MAX_RETRIES          = 5;
    private static final long   BASE_BACKOFF_MS      = 1_000L;
    private static final long   MAX_BACKOFF_MS       = 30_000L;

    private static final String PROMPT_VERSION = "v2";

    private static final List<String> MODEL_FALLBACK_CHAIN = List.of(
                    		
    		"gpt-4.1-mini", // primary
            "gpt-5-nano", // first fallback
            "gpt-4o-mini"    // last resort
    );

    /** Models that accept the `temperature` parameter on /v1/responses. */
    private static final Set<String> TEMPERATURE_SUPPORTED_MODELS =
            Set.of("gpt-4.1", "gpt-4o", "gpt-4.1-mini", "gpt-4o-mini");

    private static final Map<String, SchemaRule> RESPONSE_SCHEMA;
    static {
        RESPONSE_SCHEMA = new LinkedHashMap<>();
        RESPONSE_SCHEMA.put("Similarity Confidence Score", new SchemaRule(SchemaType.INTEGER, true));
        RESPONSE_SCHEMA.put("Reason for Score",            new SchemaRule(SchemaType.STRING,  true));
    }

    /** HTTP client — shared, thread-safe, connection-pooled. */
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(30,  TimeUnit.SECONDS)
            .writeTimeout(30,    TimeUnit.SECONDS)
            .readTimeout(120,    TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build();

    // ─── Entry Point ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        validateApiKey();
        String model     = "gpt-5-nano";
        String sentence1 = "here are the related specific content based on your questions";
        String sentence2 = "I can only answer related to product specific questions";

        try {
            SimilarityResult result = compareSentences(sentence1, sentence2);

            System.out.println("═══════════════════════════════════════");
            System.out.println("Prompt Version : " + PROMPT_VERSION);
            System.out.println("Model Used     : " + result.modelUsed());
            System.out.println("Score          : " + result.score() + "%");
            System.out.println("Threshold      : " + SIMILARITY_THRESHOLD + "%");
            System.out.println("Final Result   : " + (result.isPassing() ? "PASS" : "FAIL"));
            System.out.println("───────────────────────────────────────");
            System.out.println("Reason:\n" + result.reason());
            System.out.println("═══════════════════════════════════════");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Comparison failed after all retries and fallbacks.", e);
            System.exit(1);
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Compares two sentences using the primary model, falling back through
     * MODEL_FALLBACK_CHAIN on non-retryable failures.
     */
    public static SimilarityResult compareSentences(String s1, String s2) throws Exception {
        return compareSentences(MODEL_FALLBACK_CHAIN.get(0), s1, s2);
    }

    /**
     * Compares two sentences starting from the specified model, then falling
     * back through the remainder of MODEL_FALLBACK_CHAIN.
     */
    public static SimilarityResult compareSentences(String preferredModel, String s1, String s2)
            throws Exception {

        String prompt = buildPrompt(s1, s2);
        List<String> chain = buildFallbackChain(preferredModel);
        Exception lastException = null;

        for (String model : chain) {
            try {
                LOGGER.info("Attempting model: " + model + " [prompt " + PROMPT_VERSION + "]");
                JSONObject json = callWithRetry(model, prompt);
                validateSchema(json);
                return SimilarityResult.from(json, model, SIMILARITY_THRESHOLD);

            } catch (NonRetryableException e) {
                lastException = e;
                LOGGER.warning("Model '" + model + "' non-retryable error: "
                        + e.getMessage() + " — trying next fallback...");

            } catch (SchemaValidationException e) {
                lastException = e;
                LOGGER.warning("Model '" + model + "' returned invalid schema: "
                        + e.getMessage() + " — trying next fallback...");
            }
        }

        throw new RuntimeException(
                "All models in fallback chain exhausted: " + chain, lastException);
    }

    // ─── Fallback Chain Builder ───────────────────────────────────────────────

    /**
     * Returns a deduplicated list starting with preferred, followed by any
     * models from MODEL_FALLBACK_CHAIN not already included.
     */
    private static List<String> buildFallbackChain(String preferred) {
        if (MODEL_FALLBACK_CHAIN.contains(preferred)) {
            return MODEL_FALLBACK_CHAIN.subList(
                    MODEL_FALLBACK_CHAIN.indexOf(preferred),
                    MODEL_FALLBACK_CHAIN.size());
        }
        // Custom model not in the chain — try it first, then fall through to chain
        List<String> chain = new java.util.ArrayList<>();
        chain.add(preferred);
        chain.addAll(MODEL_FALLBACK_CHAIN);
        return chain;
    }

    // ─── HTTP & Retry Logic ───────────────────────────────────────────────────

    private static JSONObject callWithRetry(String model, String prompt) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return callApi(model, prompt);

            } catch (RateLimitException e) {
                lastException = e;
                long wait = Math.min(
                        e.retryAfterMs > 0 ? e.retryAfterMs : backoff(attempt),
                        MAX_BACKOFF_MS);
                LOGGER.warning("Rate-limited on '" + model + "'. Waiting " + wait
                        + "ms [retry " + attempt + "/" + MAX_RETRIES + "]");
                Thread.sleep(wait);

            } catch (NonRetryableException e) {
                throw e; // bubble up to fallback chain immediately

            } catch (IOException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) break;
                long wait = Math.min(backoff(attempt), MAX_BACKOFF_MS);
                LOGGER.warning("Transient error on '" + model + "': " + e.getMessage()
                        + " [retry " + attempt + "/" + MAX_RETRIES + " in " + wait + "ms]");
                Thread.sleep(wait);
            }
        }

        throw new RuntimeException(
                "Max retries (" + MAX_RETRIES + ") exceeded for model '" + model + "'.",
                lastException);
    }

    private static JSONObject callApi(String model, String prompt) throws IOException {
        JSONObject body = buildRequestBody(model, prompt);

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            handleHttpErrors(response);
            return extractJson(new JSONObject(requireBody(response)));
        }
    }

    // ─── Request Building ─────────────────────────────────────────────────────

    private static JSONObject buildRequestBody(String model, String prompt) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", prompt);

        if (TEMPERATURE_SUPPORTED_MODELS.contains(model)) {
            body.put("temperature", 0);
        }

        body.put("text", new JSONObject()
                .put("format", new JSONObject().put("type", "json_object")));

        return body;
    }

    /**
     * Builds the versioned prompt.
     * The [prompt:vN] tag is embedded for log/audit traceability — not parsed at runtime.
     * To change scoring behaviour: edit the rules below AND bump PROMPT_VERSION.
     */
    private static String buildPrompt(String s1, String s2) {
        return "You are a semantic comparison assistant.I will provide two sentences.\n\n" +
                "Your task is to:\n" 
        		+ "- Do NOT inflate similarity due to general business language.\r\n"
                + "- Do NOT increase score based only on shared structure.\r\n"
                + "- Only increase score if core intent and primary objective match.\r\n"
                + "- Do NOT decrease score due to vocabulary changes alone.\r\n"
                + "- Score must reflect semantic meaning, not writing style."
                + "Similarity Confidence Score must be an integer between 0 and 100.\r\n"
                + "Do NOT return decimal values.\r\n"
                + "Do NOT return probability scale." +
                "Output strictly in JSON format:\n\n" +
                "{\n" +
                "  \"Similarity Confidence Score\": Number,\n (0% = completely different meaning, 100% = exactly the same meaning)" +
                "  \"Reason for Score\": \"Detailed explanation including:\n" +
                "    - Key similarities\n" +
                "    - Key differences\n" +
                "    - Contextual differences\n" +
                "    - Missing or additional information\n" +
                "    - Tone or implication differences\"\n" +
                "}\n\n" +
                "=================================\n\n" +
                "Now compare:\n\n" +
                "Sentence 1: \"" + s1 + "\"\n" +
                "Sentence 2: \"" + s2 + "\"";
    }

    // ─── JSON Schema Validation ───────────────────────────────────────────────

    /**
     * Validates that json conforms to RESPONSE_SCHEMA.
     *
     * Checks per field:
     *   - Required fields must be present
     *   - Type must match SchemaType (INTEGER or STRING)
     *   - Integers must be within [0, 100]
     *   - Strings must be non-blank
     *
     * @throws SchemaValidationException with a detailed message on the first violation found
     */
    private static void validateSchema(JSONObject json) throws SchemaValidationException {
        for (Map.Entry<String, SchemaRule> entry : RESPONSE_SCHEMA.entrySet()) {
            String field     = entry.getKey();
            SchemaRule rule  = entry.getValue();

            if (!json.has(field)) {
                if (rule.required()) {
                    throw new SchemaValidationException(
                            "Required field missing: \"" + field + "\"");
                }
                continue;
            }

            if (rule.type() == SchemaType.INTEGER) {
                    if (!(json.get(field) instanceof Number)) {
                        throw new SchemaValidationException(
                                "Field \"" + field + "\" must be a number, got: "
                                        + json.get(field).getClass().getSimpleName());
                    }
                    int val = json.getInt(field);
                    if (val < 0 || val > 100) {
                        throw new SchemaValidationException(
                                "Field \"" + field + "\" must be 0-100, got: " + val);
                    }
                } else if (rule.type() == SchemaType.STRING) {
                    if (!(json.get(field) instanceof String) || ((String) json.get(field)).trim().isEmpty()) {
                        throw new SchemaValidationException(
                                "Field \"" + field + "\" must be a non-empty string.");
                    }
                }
        }
    }

    // ─── Response Parsing ─────────────────────────────────────────────────────

    private static JSONObject extractJson(JSONObject apiResponse) throws IOException {
        JSONArray output = apiResponse.optJSONArray("output");
        if (output == null) {
            throw new IOException("Response missing 'output' array. Raw: " + apiResponse);
        }

        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.getJSONObject(i);
            if (!"message".equals(item.optString("type"))) continue;

            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;

            for (int j = 0; j < content.length(); j++) {
                JSONObject block = content.getJSONObject(j);
                if ("output_text".equals(block.optString("type"))) {
                    String text = block.getString("text").strip();
                    // Strip accidental markdown fences
                    if (text.startsWith("```")) {
                        text = text.replaceAll("(?s)^```[a-zA-Z]*\\n?", "")
                                   .replaceAll("```$", "").strip();
                    }
                    return new JSONObject(text);
                }
            }
        }

        throw new IOException("No 'output_text' block found in response: " + apiResponse);
    }

    // ─── Error Handling ───────────────────────────────────────────────────────

    private static void handleHttpErrors(Response response) throws IOException {
        if (response.isSuccessful()) return;

        String errorBody = response.body() != null ? response.body().string() : "(empty)";
        int code = response.code();

        if (code == 429) {
            String retryAfter = response.header("Retry-After");
            long retryMs = retryAfter != null ? Long.parseLong(retryAfter) * 1_000L : -1;
            throw new RateLimitException("HTTP 429 Rate Limited. Body: " + errorBody, retryMs);
        }

        if (code >= 500) {
            throw new IOException("Server error " + code + ": " + errorBody);
        }

        // 4xx (except 429) — non-retryable, surfaces to fallback chain
        throw new NonRetryableException("Client error " + code + ": " + errorBody);
    }

    private static String requireBody(Response response) throws IOException {
        if (response.body() == null) throw new IOException("Empty response body.");
        return response.body().string();
    }

    private static void validateApiKey() {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY environment variable is not set. " +
                    "Export it before running: export OPENAI_API_KEY=sk-...");
        }
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    /** Exponential back-off: base x 2^(attempt-1) + random jitter up to 500ms. */
    private static long backoff(int attempt) {
        long exp    = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = (long) (Math.random() * 500);
        return exp + jitter;
    }

    // ─── Schema DSL ───────────────────────────────────────────────────────────

    enum SchemaType { INTEGER, STRING }

    static final class SchemaRule {
        private final SchemaType type;
        private final boolean required;
        SchemaRule(SchemaType type, boolean required) {
            this.type = type;
            this.required = required;
        }
        SchemaType type()    { return type; }
        boolean    required() { return required; }
    }

    // ─── Custom Exceptions ────────────────────────────────────────────────────

    static class RateLimitException extends IOException {
        final long retryAfterMs;
        RateLimitException(String msg, long retryAfterMs) {
            super(msg);
            this.retryAfterMs = retryAfterMs;
        }
    }

    /** 4xx client errors and schema mismatches — skip retries, trigger model fallback. */
    static class NonRetryableException extends IOException {
        NonRetryableException(String msg) { super(msg); }
    }

    /** Thrown when the model response does not match RESPONSE_SCHEMA. */
    static class SchemaValidationException extends Exception {
        SchemaValidationException(String msg) { super(msg); }
    }

    // ─── Result Record ────────────────────────────────────────────────────────

    /**
     * Immutable value object holding the comparison result.
     * modelUsed() may differ from the originally requested model if a fallback was triggered.
     */
    public static final class SimilarityResult {

        private final int    score;
        private final String reason;
        private final String modelUsed;
        private final int    threshold;

        private SimilarityResult(int score, String reason, String modelUsed, int threshold) {
            this.score     = score;
            this.reason    = reason;
            this.modelUsed = modelUsed;
            this.threshold = threshold;
        }

        public int     score()     { return score;     }
        public String  reason()    { return reason;    }
        public String  modelUsed() { return modelUsed; }
        public int     threshold() { return threshold; }

        public boolean isPassing() { return score >= threshold; }

        static SimilarityResult from(JSONObject json, String model, int threshold)
                throws IOException {
            try {
                return new SimilarityResult(
                        json.getInt("Similarity Confidence Score"),
                        json.getString("Reason for Score"),
                        model,
                        threshold);
            } catch (Exception e) {
                throw new IOException("Failed to map JSON to SimilarityResult: " + json, e);
            }
        }
    }
}