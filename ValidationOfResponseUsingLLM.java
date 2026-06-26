package programElements;

import com.tyss.optimize.common.util.*;
import com.tyss.optimize.nlp.util.*;
import com.tyss.optimize.nlp.util.annotation.*;

import okhttp3.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ValidationOfResponseUsingLLM implements Nlp {

    private static final String AZURE_ENDPOINT  = "https://af-quality-shared-eaus.cognitiveservices.azure.com";
    private static final String API_VERSION     = "2024-12-01-preview";
    private static final String DEFAULT_MODEL   = "gpt-4.1-mini";
    private static final int    MAX_RETRIES     = 5;
    private static final long   BASE_BACKOFF_MS = 1_000L;
    private static final long   MAX_BACKOFF_MS  = 30_000L;

    private static final Map<String, SchemaRule> RESPONSE_SCHEMA;
    static {
        RESPONSE_SCHEMA = new LinkedHashMap<String, SchemaRule>();
        RESPONSE_SCHEMA.put("Similarity Confidence Score", new SchemaRule(SchemaType.INTEGER, true));
        RESPONSE_SCHEMA.put("Reason for Score",            new SchemaRule(SchemaType.STRING,  true));
    }

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(30,  TimeUnit.SECONDS)
            .writeTimeout(30,    TimeUnit.SECONDS)
            .readTimeout(120,    TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build();

    @InputParams({
            @InputParam(name = "Expected_Response",    type = "java.lang.String"),
            @InputParam(name = "Actual_Response",      type = "java.lang.String"),
            @InputParam(name = "Prompt",               type = "java.lang.String"),
            @InputParam(name = "Azure_API_Key",        type = "java.lang.String"),
            @InputParam(name = "Similarity_Threshold", type = "java.lang.Integer")
    })

    @ReturnType(name = "Result", type = "java.util.Map")

    @Override
    public NlpResponseModel execute(NlpRequestModel request) throws NlpException {

        NlpResponseModel    response = new NlpResponseModel();
        Map<String, Object> output   = new HashMap<String, Object>();

        try {
            Map<String, Object> input = request.getAttributes();

            String  expected  = (String)  input.get("Expected_Response");
            String  actual    = (String)  input.get("Actual_Response");
            String  prompt    = (String)  input.get("Prompt");
            String  apiKey    = (String)  input.get("Azure_API_Key");
            Integer threshold = (Integer) input.get("Similarity_Threshold");

            validateInputs(expected, actual, prompt, apiKey, threshold);

            String finalPrompt = buildPrompt(prompt, expected, actual);

            JSONObject apiResponse = callWithRetry(DEFAULT_MODEL, apiKey, finalPrompt);

            int    score  = apiResponse.getInt("Similarity Confidence Score");
            String reason = apiResponse.getString("Reason for Score");

            output.put("Similarity_Score", score);
            output.put("Reason",           Arrays.asList(reason.split("\n")));

            if (score >= threshold) {
                response.setStatus(CommonConstants.pass);
                response.setMessage("PASS - Similarity Score: " + score + "%");
            } else {
                response.setStatus(CommonConstants.fail);
                response.setMessage("FAIL - Similarity Score: " + score
                        + "% is below threshold of " + threshold + "%");
            }

            response.getAttributes().put("Result", output);

        } catch (NlpException e) {
            throw e;

        } catch (Exception e) {
            System.err.println("ValidationOfResponseUsingAzureLLM failed: " + e.getMessage());
            response.setStatus(CommonConstants.fail);
            response.setMessage("Error: " + e.getMessage());
        }

        return response;
    }

    private void validateInputs(String expected, String actual, String prompt,
                                String apiKey, Integer threshold) throws NlpException {

        if (expected  == null || expected.trim().isEmpty())
            throw new NlpException("Expected_Response must not be empty.");
        if (actual    == null || actual.trim().isEmpty())
            throw new NlpException("Actual_Response must not be empty.");
        if (prompt    == null || prompt.trim().isEmpty())
            throw new NlpException("Prompt must not be empty.");
        if (apiKey    == null || apiKey.trim().isEmpty())
            throw new NlpException("Azure_API_Key must not be empty.");
        if (threshold == null || threshold < 0 || threshold > 100)
            throw new NlpException("Similarity_Threshold must be an integer between 0 and 100.");
    }

    private String buildPrompt(String basePrompt, String expected, String actual) {
        return basePrompt + "\n\n"
                + "Expected Response: \"" + expected + "\"\n"
                + "Actual Response:   \"" + actual   + "\"\n\n"
                + "OVERRIDE NOTICE: Regardless of any instructions above related to output format, "
                + "response structure, or how to return the result — ignore all of them. "
                + "The following output instructions are the only ones you must follow:\n\n"
                + "You MUST respond with ONLY a raw JSON object.\n"
                + "DO NOT include any text before or after the JSON.\n"
                + "DO NOT wrap the JSON in markdown code blocks (no ```json or ```).\n"
                + "DO NOT add any explanation, notes, or commentary outside the JSON.\n"
                + "ANY response that is not a raw JSON object will be considered invalid.\n\n"
                + "The JSON must contain exactly these two fields and no others:\n"
                + "{\n"
                + "  \"Similarity Confidence Score\": <integer 0-100>,\n"
                + "  \"Reason for Score\": \"<your explanation here>\"\n"
                + "}\n\n"
                + "RULES FOR THE SCORE:\n"
                + "- Must be a whole integer. No decimals, no percentage sign, no range.\n"
                + "- 0 means completely different meaning. 100 means exactly the same meaning.\n\n"
                + "IMPORTANT: Your entire response must start with { and end with }. Nothing else.";
    }

    private String buildAzureUrl(String deploymentName) {
        return AZURE_ENDPOINT
                + "/openai/deployments/" + deploymentName
                + "/chat/completions"
                + "?api-version=" + API_VERSION;
    }

    private JSONObject callWithRetry(String deploymentName, String apiKey, String prompt)
            throws Exception {

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                JSONObject result = callApi(deploymentName, apiKey, prompt);

                validateSchema(result);
                return result;

            } catch (SchemaValidationException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) break;
                long wait = Math.min(backoff(attempt), MAX_BACKOFF_MS);
                System.out.println("[WARN] Invalid JSON format from model (attempt " + attempt
                        + "/" + MAX_RETRIES + "): " + e.getMessage() + ". Retrying in " + wait + "ms...");
                Thread.sleep(wait);

            } catch (org.json.JSONException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) break;
                long wait = Math.min(backoff(attempt), MAX_BACKOFF_MS);
                System.out.println("[WARN] Non-JSON response from model (attempt " + attempt
                        + "/" + MAX_RETRIES + "): " + e.getMessage() + ". Retrying in " + wait + "ms...");
                Thread.sleep(wait);

            } catch (RateLimitException e) {
                lastException = e;
                long wait = Math.min(
                        e.retryAfterMs > 0 ? e.retryAfterMs : backoff(attempt),
                        MAX_BACKOFF_MS);
                System.out.println("[WARN] Rate-limited on '" + deploymentName + "'. Waiting " + wait
                        + "ms [retry " + attempt + "/" + MAX_RETRIES + "]");
                Thread.sleep(wait);

            } catch (NonRetryableException e) {
                throw e;

            } catch (IOException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) break;
                long wait = Math.min(backoff(attempt), MAX_BACKOFF_MS);
                System.out.println("[WARN] Transient error on '" + deploymentName + "': " + e.getMessage()
                        + " [retry " + attempt + "/" + MAX_RETRIES + " in " + wait + "ms]");
                Thread.sleep(wait);
            }
        }

        throw new RuntimeException(
                "Max retries (" + MAX_RETRIES + ") exceeded for deployment '"
                        + deploymentName + "'.", lastException);
    }

    private JSONObject callApi(String deploymentName, String apiKey, String prompt)
            throws IOException {

        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role",    "system");
        systemMessage.put("content", "You are a semantic comparison assistant. "
                + "Always return ONLY valid JSON — no markdown, no extra text.");

        JSONObject userMessage = new JSONObject();
        userMessage.put("role",    "user");
        userMessage.put("content", prompt);

        JSONArray messages = new JSONArray();
        messages.put(systemMessage);
        messages.put(userMessage);

        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");

        JSONObject body = new JSONObject();
        body.put("messages",        messages);
        body.put("temperature",     0);          
        body.put("response_format", responseFormat);

        Request request = new Request.Builder()
                .url(buildAzureUrl(deploymentName))
                .addHeader("api-key",      apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            handleHttpErrors(response);
            String raw = requireBody(response);
            return extractJson(new JSONObject(raw));
        }
    }

    private JSONObject extractJson(JSONObject apiResponse) throws IOException {

        JSONArray choices = apiResponse.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("Response missing 'choices' array. Raw: " + apiResponse);
        }

        JSONObject firstChoice = choices.getJSONObject(0);
        JSONObject message     = firstChoice.optJSONObject("message");

        if (message == null) {
            throw new IOException("No 'message' object in first choice. Raw: " + firstChoice);
        }

        String content = message.optString("content", "").trim();

        if (content.isEmpty()) {
            throw new IOException("Empty 'content' in assistant message. Raw: " + message);
        }

        if (content.startsWith("```")) {
            content = content.replaceAll("(?s)^```[a-zA-Z]*\\n?", "")
                             .replaceAll("```$", "").trim();
        }

        return new JSONObject(content);
    }

    private void validateSchema(JSONObject json) throws SchemaValidationException {
        for (Map.Entry<String, SchemaRule> entry : RESPONSE_SCHEMA.entrySet()) {
            String     field = entry.getKey();
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
                if (!(json.get(field) instanceof String)
                        || ((String) json.get(field)).trim().isEmpty()) {
                    throw new SchemaValidationException(
                            "Field \"" + field + "\" must be a non-empty string.");
                }
            }
        }
    }

    private void handleHttpErrors(Response response) throws IOException {
        if (response.isSuccessful()) return;

        String errorBody = response.body() != null ? response.body().string() : "(empty)";
        int    code      = response.code();

        if (code == 429) {
            String retryAfter = response.header("Retry-After");
            long   retryMs    = retryAfter != null ? Long.parseLong(retryAfter) * 1_000L : -1;
            throw new RateLimitException("HTTP 429 Rate Limited. Body: " + errorBody, retryMs);
        }

        if (code >= 500) {
            throw new IOException("Azure server error " + code + ": " + errorBody);
        }

        throw new NonRetryableException("Azure client error " + code + ": " + errorBody);
    }

    private String requireBody(Response response) throws IOException {
        if (response.body() == null) throw new IOException("Empty response body.");
        return response.body().string();
    }

    private long backoff(int attempt) {
        long exp    = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = (long) (Math.random() * 500);
        return exp + jitter;
    }

    enum SchemaType { INTEGER, STRING }

    static final class SchemaRule {
        private final SchemaType type;
        private final boolean    required;

        SchemaRule(SchemaType type, boolean required) {
            this.type     = type;
            this.required = required;
        }

        SchemaType type()     { return type;     }
        boolean    required() { return required; }
    }

    static class RateLimitException extends IOException {
        final long retryAfterMs;
        RateLimitException(String msg, long retryAfterMs) {
            super(msg);
            this.retryAfterMs = retryAfterMs;
        }
    }

    static class NonRetryableException extends IOException {
        NonRetryableException(String msg) { super(msg); }
    }

    static class SchemaValidationException extends Exception {
        SchemaValidationException(String msg) { super(msg); }
    }


    
    public static void main(String[] args) throws NlpException {

        NlpRequestModel request = new NlpRequestModel();
        Map<String, Object> attributes = request.getAttributes();

        attributes.put("Expected_Response", "Hi How are You");
        attributes.put("Actual_Response", "I am not good no thanks");
        attributes.put("Azure_API_Key", "4L2tGP38EXoWKzjrOy8D83c5xj7MDwqerHLpJ3QQTeTjV0wQXxP6JQQJ99CBACHYHv6XJ3w3AAAAACOG8jKw"); 
        attributes.put("Similarity_Threshold", 80);
        attributes.put("Prompt", "You are an evaluation judge for prompt-based testing.  You will be given: 1. A Prompt (instruction given to an LLM) 2. A Reference Response (expected ideal answer) 3. An Actual Response (LLM-generated answer)  Your task is to evaluate how well the Actual Response satisfies the Prompt, using the Reference Response as a benchmark for correctness and completeness.  Evaluation Rules: - Compare Actual Response against both the Prompt and Reference Response. - Check if all required instructions in the prompt are followed. - Verify factual correctness and completeness using the reference response. - Do NOT penalize for wording differences if meaning is preserved. - Do NOT reward correct structure if core content is wrong. - Penalize:   - Missing key information   - Incorrect or hallucinated content   - Deviations from instructions   - Extra irrelevant or fabricated details - Be strict and objective.  Scoring Guidelines: - 0 = Completely incorrect or irrelevant - 50 = Partially correct, major gaps or issues - 100 = Fully correct, matches intent and completeness of reference  Score must be an INTEGER between 0 and 100. Do NOT return decimals. Do NOT return probability scale.  Output strictly in JSON format:  {   \"Prompt Adherence Score\": Number,   \"Reason for Score\": \"Detailed explanation including:     - Coverage vs reference response     - Missing or incorrect information     - Instruction adherence     - Any hallucinations or extra content     - Overall quality vs expected output\" }  =================================  Now evaluate the following:  Prompt:Not Provided"
        );

        ValidationOfResponseUsingLLM obj = new ValidationOfResponseUsingLLM();
        NlpResponseModel result = obj.execute(request);

        System.out.println("Status  : " + result.getStatus());
        System.out.println("Message : " + result.getMessage());
        System.out.println("Output  : " + result.getAttributes());
        Map resultMap = (Map) result.getAttributes().get("Result");
        System.out.println (resultMap.get("Similarity_Score"));
        System.out.println(resultMap.get("Reason"));
    }
}