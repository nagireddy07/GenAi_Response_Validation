package programElements;
 
import com.tyss.optimize.common.util.*;
import com.tyss.optimize.nlp.util.*;
import com.tyss.optimize.nlp.util.annotation.*;
 
import okhttp3.*;
 
import org.json.JSONArray;
import org.json.JSONObject;
 
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
 
// Word (.docx) parser — requires: org.apache.poi:poi-ooxml:5.2.3
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
 
// PDF parser — requires: org.apache.pdfbox:pdfbox:3.0.1
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
 
public class GenerateQueriesUsingLLM implements Nlp {
 
    // ─── Azure Configuration ──────────────────────────────────────────────────
 
    private static final String AZURE_ENDPOINT  = "https://af-quality-shared-eaus.cognitiveservices.azure.com";
    private static final String API_VERSION     = "2025-01-01-preview";
    private static final String DEFAULT_MODEL   = "gpt-4.1-mini";
    private static final int    MAX_RETRIES     = 5;
    private static final long   BASE_BACKOFF_MS = 1_000L;
    private static final long   MAX_BACKOFF_MS  = 30_000L;
 
    // ─── HTTP Client ──────────────────────────────────────────────────────────
 
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(30,  TimeUnit.SECONDS)
            .writeTimeout(30,    TimeUnit.SECONDS)
            .readTimeout(180,    TimeUnit.SECONDS)   // longer timeout — document processing
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build();
 
    // ─── Input / Output Declarations ──────────────────────────────────────────
 
    @InputParams({
            @InputParam(name = "Document_File_Path", type = "java.lang.nlp.filepath"),
            @InputParam(name = "Query_Count",        type = "java.lang.Integer"),
            @InputParam(name = "Prompt",             type = "java.lang.String"),
            @InputParam(name = "Azure_API_Key",      type = "java.lang.String")
    })
 
    @ReturnType(name = "Result", type = "java.util.Map")
 
    // ─── Execute ──────────────────────────────────────────────────────────────
 
    @Override
    public NlpResponseModel execute(NlpRequestModel request) throws NlpException {
 
        NlpResponseModel    response = new NlpResponseModel();
        Map<String, Object> output   = new HashMap<String, Object>();
 
        try {
            Map<String, Object> input = request.getAttributes();
 
            byte[]  fileBytes  = (byte[])  input.get("Document_File_Path");
            Integer queryCount = (Integer) input.get("Query_Count");
            String  prompt     = (String)  input.get("Prompt");
            String  apiKey     = (String)  input.get("Azure_API_Key");

            validateInputs(fileBytes, queryCount, prompt, apiKey);

            String documentContent = readFromBytes(fileBytes);

            String finalPrompt = buildPrompt(prompt, documentContent, queryCount);
            JSONArray generatedPairs = callWithRetry(DEFAULT_MODEL, apiKey, finalPrompt);
 
            // Convert JSONArray → List<Map<String, String>>
            List<Map<String, Object>> queryList = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < generatedPairs.length(); i++) {
                JSONObject pair = generatedPairs.getJSONObject(i);
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("Query",             pair.getString("Query"));
                entry.put("Expected_Response", pair.getString("Expected_Response"));
                queryList.add(entry);
            }
 
            output.put("Generated_Pairs", queryList);
            output.put("Total_Generated",  queryList.size());
 
            response.getAttributes().put("Result", output);
            response.setStatus(CommonConstants.pass);
            response.setMessage("PASS - Generated " + queryList.size() + " query-response pairs.");
 
        } catch (NlpException e) {
            throw e;
 
        } catch (Exception e) {
            System.err.println("GenerateQueriesUsingLLM failed: " + e.getMessage());
            response.setStatus(CommonConstants.fail);
            response.setMessage("Error: " + e.getMessage());
        }
 
        return response;
    }
 
    // ─── Input Validation ─────────────────────────────────────────────────────
 
    private void validateInputs(byte[] fileBytes, Integer queryCount,
                                String prompt, String apiKey) throws NlpException {
 
        if (fileBytes == null || fileBytes.length == 0)
            throw new NlpException("Document_File_Path must not be empty.");
        if (queryCount == null || queryCount < 1)
            throw new NlpException("Query_Count must be a positive integer.");
        if (queryCount > 50)
            throw new NlpException("Query_Count must not exceed 50.");
        if (prompt == null || prompt.trim().isEmpty())
            throw new NlpException("Prompt must not be empty.");
        if (apiKey == null || apiKey.trim().isEmpty())
            throw new NlpException("Azure_API_Key must not be empty.");
    }
 
    // ─── Document Reader ──────────────────────────────────────────────────────
    //
    //  Receives raw bytes from Fireflink test data (java.lang.nlp.filepath).
    //  Detects file type automatically using magic bytes (file signature):
    //    - PDF  : starts with %PDF  → parsed with PDFBox
    //    - DOCX : starts with PK    → parsed with Apache POI
    //    - TXT  : everything else   → read as plain UTF-8 text
    //
 
    private String readFromBytes(byte[] fileBytes) throws IOException {
 
        if (isPdf(fileBytes)) {
            return readPdfFromBytes(fileBytes);
        } else if (isDocx(fileBytes)) {
            return readDocxFromBytes(fileBytes);
        } else {
            // Treat as plain text
            String text = new String(fileBytes, "UTF-8").trim();
            if (text.isEmpty()) {
                throw new IOException("Document is empty — no text content found.");
            }
            return text;
        }
    }
 
    // PDF magic bytes: %PDF = 0x25 0x50 0x44 0x46
    private boolean isPdf(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 0x25 && bytes[1] == 0x50
                && bytes[2] == 0x44 && bytes[3] == 0x46;
    }
 
    // DOCX magic bytes: PK = 0x50 0x4B (ZIP format, same as .docx)
    private boolean isDocx(byte[] bytes) {
        return bytes.length >= 2
                && bytes[0] == 0x50 && bytes[1] == 0x4B;
    }
 
    private String readPdfFromBytes(byte[] fileBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            if (document.isEncrypted()) {
                throw new IOException("PDF is encrypted and cannot be read.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document).trim();
            if (text.isEmpty()) {
                throw new IOException(
                        "No text extracted from PDF. The file may contain only scanned images.");
            }
            return text;
        }
    }
 
    private String readDocxFromBytes(byte[] fileBytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(fileBytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : document.getParagraphs()) {
                String text = para.getText().trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n");
                }
            }
            String result = sb.toString().trim();
            if (result.isEmpty()) {
                throw new IOException("No text extracted from Word document.");
            }
            return result;
        }
    }
 
    // ─── Prompt Builder ───────────────────────────────────────────────────────
 
    private String buildPrompt(String basePrompt, String documentContent, int queryCount) {
        return basePrompt + "\n\n"
                + "DOCUMENT CONTENT:\n"
                + "=================\n"
                + documentContent + "\n"
                + "=================\n\n"
                + "OVERRIDE NOTICE: Regardless of any instructions above related to output format, "
                + "response structure, or how to return the result — ignore all of them. "
                + "The following output instructions are the only ones you must follow:\n\n"
                + "Generate exactly " + queryCount + " query and expected response pairs "
                + "strictly based on the document content above.\n\n"
                + "You MUST respond with ONLY a raw JSON array — no markdown, no extra text, "
                + "no explanation before or after.\n"
                + "DO NOT wrap the response in ```json or ``` code blocks.\n"
                + "IMPORTANT: Your entire response must start with [ and end with ].\n\n"
                + "Each element in the array must be a JSON object with exactly these two fields:\n"
                + "[\n"
                + "  {\n"
                + "    \"Query\": \"<a question based on the document>\",\n"
                + "    \"Expected_Response\": \"<the accurate answer from the document>\"\n"
                + "  }\n"
                + "]\n\n"
                + "RULES:\n"
                + "- Every Query must be directly answerable from the document content.\n"
                + "- Expected_Response must be accurate and derived only from the document.\n"
                + "- Do NOT add any fields other than Query and Expected_Response.\n"
                + "- Do NOT include any text outside the JSON array.\n"
                + "- The array must contain exactly " + queryCount + " objects.";
    }
 
    // ─── Azure API URL Builder ────────────────────────────────────────────────
 
    private String buildAzureUrl(String deploymentName) {
        return AZURE_ENDPOINT
                + "/openai/deployments/" + deploymentName
                + "/chat/completions"
                + "?api-version=" + API_VERSION;
    }
 
    // ─── Retry Logic ──────────────────────────────────────────────────────────
 
    private JSONArray callWithRetry(String deploymentName, String apiKey, String prompt)
            throws Exception {
 
        Exception lastException = null;
 
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                JSONArray result = callApi(deploymentName, apiKey, prompt);
                validateResponseArray(result);
                return result;
 
            } catch (InvalidResponseException e) {
                // Model returned wrong format — retry
                lastException = e;
                if (attempt == MAX_RETRIES) break;
                long wait = Math.min(backoff(attempt), MAX_BACKOFF_MS);
                System.out.println("[WARN] Invalid response format from model (attempt "
                        + attempt + "/" + MAX_RETRIES + "): " + e.getMessage()
                        + ". Retrying in " + wait + "ms...");
                Thread.sleep(wait);
 
            } catch (org.json.JSONException e) {
                // Model returned non-parseable response — retry
                lastException = e;
                if (attempt == MAX_RETRIES) break;
                long wait = Math.min(backoff(attempt), MAX_BACKOFF_MS);
                System.out.println("[WARN] Non-JSON response from model (attempt "
                        + attempt + "/" + MAX_RETRIES + "): " + e.getMessage()
                        + ". Retrying in " + wait + "ms...");
                Thread.sleep(wait);
 
            } catch (RateLimitException e) {
                lastException = e;
                long wait = Math.min(
                        e.retryAfterMs > 0 ? e.retryAfterMs : backoff(attempt),
                        MAX_BACKOFF_MS);
                System.out.println("[WARN] Rate-limited on '" + deploymentName + "'. Waiting "
                        + wait + "ms [retry " + attempt + "/" + MAX_RETRIES + "]");
                Thread.sleep(wait);
 
            } catch (NonRetryableException e) {
                throw e;  // 4xx — no point retrying
 
            } catch (IOException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) break;
                long wait = Math.min(backoff(attempt), MAX_BACKOFF_MS);
                System.out.println("[WARN] Transient error on '" + deploymentName + "': "
                        + e.getMessage() + " [retry " + attempt + "/" + MAX_RETRIES
                        + " in " + wait + "ms]");
                Thread.sleep(wait);
            }
        }
 
        throw new RuntimeException(
                "Max retries (" + MAX_RETRIES + ") exceeded for deployment '"
                        + deploymentName + "'.", lastException);
    }
 
    // ─── Azure API Call ───────────────────────────────────────────────────────
 
    private JSONArray callApi(String deploymentName, String apiKey, String prompt)
            throws IOException {
 
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role",    "system");
        systemMessage.put("content", "You are a query-response generator. "
                + "You read documents and generate question-answer pairs strictly from the content. "
                + "Always return ONLY a raw JSON array — no markdown, no extra text.");
 
        JSONObject userMessage = new JSONObject();
        userMessage.put("role",    "user");
        userMessage.put("content", prompt);
 
        JSONArray messages = new JSONArray();
        messages.put(systemMessage);
        messages.put(userMessage);
 
        JSONObject body = new JSONObject();
        body.put("messages",    messages);
        body.put("temperature", 0);
 
        Request request = new Request.Builder()
                .url(buildAzureUrl(deploymentName))
                .addHeader("api-key",      apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .build();
 
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            handleHttpErrors(response);
            String raw = requireBody(response);
            return extractJsonArray(new JSONObject(raw));
        }
    }
 
    // ─── Response Parsing ─────────────────────────────────────────────────────
 
    private JSONArray extractJsonArray(JSONObject apiResponse) throws IOException {
 
        JSONArray choices = apiResponse.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("Response missing 'choices' array. Raw: " + apiResponse);
        }
 
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) {
            throw new IOException("No 'message' in first choice. Raw: " + choices.getJSONObject(0));
        }
 
        String content = message.optString("content", "").trim();
        if (content.isEmpty()) {
            throw new IOException("Empty 'content' in assistant message.");
        }
 
        // Strip accidental markdown fences
        if (content.startsWith("```")) {
            content = content.replaceAll("(?s)^```[a-zA-Z]*\\n?", "")
                             .replaceAll("```$", "").trim();
        }
 
        // Must be a JSON array
        if (!content.startsWith("[")) {
            throw new org.json.JSONException(
                    "Expected JSON array starting with '[', got: "
                    + content.substring(0, Math.min(100, content.length())));
        }
 
        return new JSONArray(content);
    }
 
    // ─── Response Validation ──────────────────────────────────────────────────
 
    private void validateResponseArray(JSONArray array) throws InvalidResponseException {
        if (array == null || array.length() == 0) {
            throw new InvalidResponseException("Model returned an empty array.");
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj;
            try {
                obj = array.getJSONObject(i);
            } catch (Exception e) {
                throw new InvalidResponseException("Element at index " + i + " is not a JSON object.");
            }
            if (!obj.has("Query") || obj.optString("Query").trim().isEmpty()) {
                throw new InvalidResponseException(
                        "Element at index " + i + " is missing or has empty 'Query' field.");
            }
            if (!obj.has("Expected_Response") || obj.optString("Expected_Response").trim().isEmpty()) {
                throw new InvalidResponseException(
                        "Element at index " + i + " is missing or has empty 'Expected_Response' field.");
            }
        }
    }
 
    // ─── HTTP Error Handling ──────────────────────────────────────────────────
 
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
 
    // ─── Utility ──────────────────────────────────────────────────────────────
 
    private long backoff(int attempt) {
        long exp    = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = (long) (Math.random() * 500);
        return exp + jitter;
    }
 
    // ─── Custom Exceptions ────────────────────────────────────────────────────
 
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
 
    /** Thrown when the model response array is missing required fields. */
    static class InvalidResponseException extends Exception {
        InvalidResponseException(String msg) { super(msg); }
    }

    
    public static void main(String[] args) throws NlpException {

        NlpRequestModel request = new NlpRequestModel();
        Map<String, Object> attributes = request.getAttributes();

        attributes.put("Azure_API_Key", "4L2tGP38EXoWKzjrOy8D83c5xj7MDwqerHLpJ3QQTeTjV0wQXxP6JQQJ99CBACHYHv6XJ3w3AAAAACOG8jKw"); 
        attributes.put("Prompt", "You are a query-response generator trained on the provided document.\n"
        		+ "\n"
        		+ "Read the document carefully and generate queries that test understanding of the key concepts, facts, and information present in it.\n"
        		+ "\n"
        		+ "Guidelines for generating queries:\n"
        		+ "- Queries must be clear, specific, and directly answerable from the document.\n"
        		+ "- Queries should cover different sections or topics within the document.\n"
        		+ "- Avoid vague or generic queries that could be answered without reading the document.\n"
        		+ "- Avoid duplicate or very similar queries.\n"
        		+ "- Expected responses must be accurate, concise, and derived strictly from the document content.\n"
        		+ "- Do not add any information in the expected response that is not present in the document.");
        
        
        attributes.put("Document_File_Path", "C:\\Users\\User\\Downloads\\Data_Source_LLM.pdf");
        
        attributes.put("Query_Count", 5);

        GenerateQueriesUsingLLM generator = new GenerateQueriesUsingLLM();
        try {
            NlpResponseModel response = generator.execute(request);
 
            System.out.println("═══════════════════════════════════════════════════");
            System.out.println("Status  : " + response.getStatus());
            System.out.println("Message : " + response.getMessage());
            System.out.println("═══════════════════════════════════════════════════");
 
            Map<String, Object> result = response.getAttributes();
            if (result != null && result.containsKey("Result")) {
                Map<String, Object> output = (Map<String, Object>) result.get("Result");
                int total = (Integer) output.get("Total_Generated");
                List<Map<String, Object>> pairs =
                        (List<Map<String, Object>>) output.get("Generated_Pairs");
 
                System.out.println("Total Generated : " + total);
                System.out.println("───────────────────────────────────────────────────");
 
                for (int i = 0; i < pairs.size(); i++) {
                    Map<String, Object> pair = pairs.get(i);
                    System.out.println("Pair " + (i + 1) + ":");
                    System.out.println("  Query             : " + pair.get("Query"));
                    System.out.println("  Expected Response : " + pair.get("Expected_Response"));
                    System.out.println("───────────────────────────────────────────────────");
                }
            }
 
        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
        }

    }
}