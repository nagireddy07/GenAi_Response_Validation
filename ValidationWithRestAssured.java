package GPT;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.SocketTimeoutException;
import java.util.Set;

public class ValidationWithRestAssured {

    // ══════════════════════════════════════════════════════════════════════════
    //  Configuration
    // ══════════════════════════════════════════════════════════════════════════

    private static final String API_KEY             = "";
    private static final String BASE_URI            = "https://api.openai.com";
    private static final String API_PATH            = "/v1/responses";

    private static final int  SIMILARITY_THRESHOLD  = 75;
    private static final int  MAX_RETRIES           = 5;
    private static final long BASE_RETRY_DELAY_MS   = 2_000L;   // exponential: 2s→4s→8s→16s→32s
    private static final int  HTTP_TOO_MANY         = 429;

    private static final int  CONNECT_TIMEOUT_MS    = 30_000;
    private static final int  SOCKET_TIMEOUT_MS     = 120_000;

    private static final Set<String> TEMP_SUPPORTED_MODELS =
            Set.of("gpt-4.1", "gpt-4o", "gpt-4.1-mini");

    private static final RestAssuredConfig RA_CONFIG = RestAssuredConfig.config()
            .httpClient(HttpClientConfig.httpClientConfig()
                    .setParam("http.connection.timeout",         CONNECT_TIMEOUT_MS)
                    .setParam("http.socket.timeout",             SOCKET_TIMEOUT_MS)
                    .setParam("http.connection-manager.max-total",        20)
                    .setParam("http.connection-manager.max-per-route",    20)
                    .reuseHttpClientInstance());   // ← single pooled instance across all requests

    static {
        RestAssured.baseURI = BASE_URI;
    }

    public static void main(String[] args) throws Exception {

        validateApiKey();

        String model     = "gpt-5-nano";
        String sentence1 = "here are the related specific content based on your questions";
        String sentence2 = "\"I can only answer related to product specific questions\"";
        System.out.printf("[INFO] Starting similarity check | model=%s%n", model);

        SimilarityResult result = compareSentences(model, sentence1, sentence2);

        System.out.printf("[INFO] Model        : %s%n",   model);
        System.out.printf("[INFO] Similarity   : %d%%%n", result.score());
        System.out.printf("[INFO] Reason       : %s%n",   result.reason());
        System.out.printf("[INFO] Final Result : %s%n",   result.passes(SIMILARITY_THRESHOLD) ? "PASS" : "FAIL");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Compares two sentences semantically.
     *
     * @param model     OpenAI model identifier (e.g. "gpt-4o")
     * @param sentence1 first sentence
     * @param sentence2 second sentence
     * @return {@link SimilarityResult} with score (0–100) and reason
     */
    public static SimilarityResult compareSentences(String model,
                                                    String sentence1,
                                                    String sentence2) throws Exception {
        JSONObject raw = callWithRetry(model, buildPrompt(sentence1, sentence2));
        return new SimilarityResult(
                raw.getInt("Similarity Confidence Score"),
                raw.getString("Reason for Score")
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Retry — handles 429 Rate Limit + SocketTimeout
    // ══════════════════════════════════════════════════════════════════════════

    private static JSONObject callWithRetry(String model, String prompt) throws Exception {

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return callModel(model, prompt);

            } catch (RateLimitException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) break;

                // Respect Retry-After header; fall back to exponential back-off
                long waitMs = e.retryAfterMs > 0
                        ? e.retryAfterMs
                        : BASE_RETRY_DELAY_MS * (1L << (attempt - 1));  // 2s→4s→8s→16s→32s

                System.out.printf("[WARN] 429 Rate Limit (attempt %d/%d). Retrying in %dms...%n",
                        attempt, MAX_RETRIES, waitMs);
                Thread.sleep(waitMs);

            } catch (SocketTimeoutException | java.net.SocketException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) break;

                long waitMs = BASE_RETRY_DELAY_MS * attempt;
                System.out.printf("[WARN] Network error on attempt %d/%d (%s). Retrying in %dms...%n",
                        attempt, MAX_RETRIES, e.getClass().getSimpleName(), waitMs);
                Thread.sleep(waitMs);

            } catch (ApiException e) {
                // Non-retryable HTTP errors (4xx except 429) — fail fast
                System.out.printf("[ERROR] Non-retryable API error: %s%n", e.getMessage());
                throw e;
            }
        }

        System.out.printf("[ERROR] All %d retries exhausted. Last error: %s%n",
                MAX_RETRIES, lastException != null ? lastException.getMessage() : "unknown");
        throw new RuntimeException("All retries exhausted", lastException);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HTTP call via Rest Assured
    // ══════════════════════════════════════════════════════════════════════════

    private static JSONObject callModel(String model, String prompt) throws Exception {

        JSONObject body = buildRequestBody(model, prompt);

        System.out.printf("[DEBUG] Sending request | model=%s%n", model);

        Response response = RestAssured
                .given()
                    .config(RA_CONFIG)
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(body.toString())
                .when()
                    .post(API_PATH)
                .then()
                    .extract()
                    .response();

        int statusCode = response.statusCode();

        // ── 429 Rate Limit ────────────────────────────────────────────────────
        if (statusCode == HTTP_TOO_MANY) {
            String retryAfterHeader = response.getHeader("Retry-After");
            long retryAfterMs = parseRetryAfterMs(retryAfterHeader);
            System.out.printf("[WARN] Rate limited (429). Retry-After=%s | body=%s%n",
                    retryAfterHeader, response.getBody().asString());
            throw new RateLimitException(retryAfterMs);
        }

        // ── Other 4xx / 5xx ───────────────────────────────────────────────────
        if (statusCode < 200 || statusCode >= 300) {
            String errorBody = response.getBody().asString();
            System.out.printf("[ERROR] HTTP %d | body=%s%n", statusCode, errorBody);
            // 5xx is retryable; 4xx (except 429) is not
            if (statusCode >= 500) throw new SocketTimeoutException("Server error HTTP " + statusCode);
            throw new ApiException("HTTP " + statusCode + ": " + errorBody);
        }

        System.out.println("[DEBUG] API response received successfully");
        return extractOutputText(new JSONObject(response.getBody().asString()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Request body builder
    // ══════════════════════════════════════════════════════════════════════════

    private static JSONObject buildRequestBody(String model, String prompt) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", prompt);
        body.put("text", new JSONObject()
                .put("format", new JSONObject().put("type", "json_object")));

        if (TEMP_SUPPORTED_MODELS.contains(model)) {
            body.put("temperature", 0);   // deterministic output for supported models
        }
        return body;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Response parsing
    // ══════════════════════════════════════════════════════════════════════════

    private static JSONObject extractOutputText(JSONObject response) throws ApiException {
        JSONArray output = response.optJSONArray("output");
        if (output == null) throw new ApiException("Missing 'output' array in API response.");

        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.getJSONObject(i);
            if (!"message".equals(item.optString("type"))) continue;

            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;

            for (int j = 0; j < content.length(); j++) {
                JSONObject block = content.getJSONObject(j);
                if ("output_text".equals(block.optString("type"))) {
                    String text = block.getString("text");
                    return new JSONObject(text);
                }
            }
        }
        throw new ApiException("No output_text block found in API response.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Prompt builder
    // ══════════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static long parseRetryAfterMs(String header) {
        if (header == null) return 0L;
        try {
            return Long.parseLong(header.trim()) * 1_000L;
        } catch (NumberFormatException e) {
            return 0L;  // non-numeric value → fall back to exponential back-off
        }
    }

    private static void validateApiKey() {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IllegalStateException(
                "[ERROR] OPENAI_API_KEY environment variable is not set. " +
                "Export it before running: export OPENAI_API_KEY=sk-...");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Custom exceptions
    // ══════════════════════════════════════════════════════════════════════════

    /** Thrown on HTTP 429 — carries Retry-After duration in milliseconds. */
    static class RateLimitException extends Exception {
        final long retryAfterMs;
        RateLimitException(long retryAfterMs) {
            super("429 Too Many Requests");
            this.retryAfterMs = retryAfterMs;
        }
    }

    /** Thrown on non-retryable HTTP errors (4xx except 429). */
    static class ApiException extends Exception {
        ApiException(String message) { super(message); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Value object
    // ══════════════════════════════════════════════════════════════════════════

    public static final class SimilarityResult {

        private final int    score;
        private final String reason;

        public SimilarityResult(int score, String reason) {
            this.score  = score;
            this.reason = reason;
        }

        public int    score()                    { return score; }
        public String reason()                   { return reason; }
        public boolean passes(int threshold)     { return score >= threshold; }

        @Override
        public String toString() {
            return "SimilarityResult{score=" + score + ", reason='" + reason + "'}";
        }
    }
}