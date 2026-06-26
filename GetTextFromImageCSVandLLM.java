package GPT;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GetTextFromImageCSVandLLM {


    private static final Logger LOGGER = Logger.getLogger(GetTextFromImageCSVandLLM.class.getName());

    private static final String API_KEY         = "";  
    private static final String API_URL         = "https://api.openai.com/v1/responses";
    private static final int    MAX_RETRIES     = 5;
    private static final long   BASE_BACKOFF_MS = 1_000L;
    private static final long   MAX_BACKOFF_MS  = 30_000L;

    private static final List<String> MODEL_FALLBACK_CHAIN = List.of(
    		"gpt-5-nano",
            "gpt-4.1-mini",  
            "gpt-4o-mini"   
    );

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(30,  TimeUnit.SECONDS)
            .writeTimeout(30,    TimeUnit.SECONDS)
            .readTimeout(120,    TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build();


    public static void main(String[] args) throws Exception {
    	
    	installPackages();
    	
    	String imagePath = "C:\\Users\\User\\Downloads\\captcha.png";
    	
    	String script =
    			"import cv2\n" +
    			"import warnings\n" +
    			"\n" +
    			"import numpy as np\n" +
    			"from PIL import Image\n" +
    			"#warnings.filterwarnings(\"ignore\")\n" +
    			"# === Paths ===\n" +
    			"input_path = r'"+imagePath+"'\n" +
    			"output_path = r'"+imagePath+"'\n" +
    			"\n" +
    			"\n" +
    			"# === Load image ===\n" +
    			"img = cv2.imread(input_path)\n" +
    			"if img is None:\n" +
    			"    print('❌ Error: Image not found')\n" +
    			"    exit()\n" +
    			"\n" +
    			"# === Convert BGR → RGB ===\n" +
    			"img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)\n" +
    			"\n" +
    			"# === Target color filtering ===\n" +
    			"target = np.array([6, 10, 90], dtype=int)\n" +
    			"tolerance = 90\n" +
    			"\n" +
    			"# Convert to int to prevent overflow when subtracting\n" +
    			"diff = img_rgb.astype(int) - target\n" +
    			"distance_sq = np.sum(diff ** 2, axis=2)\n" +
    			"mask = distance_sq <= (tolerance ** 2)\n" +
    			"\n" +
    			"# Create filtered image\n" +
    			"filtered = np.zeros_like(img_rgb)\n" +
    			"filtered[mask] = [255, 255, 255]\n" +
    			"filtered[~mask] = [0, 0, 0]\n" +
    			"\n" +
    			"# === Convert to grayscale ===\n" +
    			"gray = cv2.cvtColor(filtered, cv2.COLOR_RGB2GRAY)\n" +
    			"\n" +
    			"# === Upscale (important for detail) ===\n" +
    			"gray = cv2.resize(gray, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)\n" +
    			"\n" +
    			"# === Light denoise ===\n" +
    			"gray = cv2.GaussianBlur(gray, (3, 3), 0)\n" +
    			"\n" +
    			"# =========================\n" +
    			"# 🔥 SHARPENING\n" +
    			"# =========================\n" +
    			"blur = cv2.GaussianBlur(gray, (0, 0), sigmaX=1.2)\n" +
    			"sharp = cv2.addWeighted(gray, 1.7, blur, -0.7, 0)\n" +
    			"\n" +
    			"laplacian = cv2.Laplacian(sharp, cv2.CV_64F)\n" +
    			"laplacian = np.uint8(np.absolute(laplacian))\n" +
    			"sharp = cv2.addWeighted(sharp, 1.3, laplacian, 0.3, 0)\n" +
    			"\n" +
    			"# =========================\n" +
    			"# ✅ FINAL THRESHOLD\n" +
    			"# =========================\n" +
    			"_, thresh = cv2.threshold(sharp, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)\n" +
    			"\n" +
    			"# =========================\n" +
    			"# 🧹 CLEANUP\n" +
    			"# =========================\n" +
    			"kernel = np.ones((2, 2), np.uint8)\n" +
    			"thresh = cv2.morphologyEx(thresh, cv2.MORPH_OPEN, kernel)\n" +
    			"thresh = cv2.medianBlur(thresh, 3)\n" +
    			"\n" +
    			"# === Save enhanced image ===\n" +
    			"cv2.imwrite(output_path, thresh)\n";
    	
    	ProcessBuilder pb = new ProcessBuilder("python", "-c", script);
    	
    	pb.redirectErrorStream(true);
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        List<String> res=new ArrayList<String>();
        String line;
        while ((line = reader.readLine()) != null) {
            
            res.add(line);
        }
        System.out.println(res);
    	
        validateApiKey();
        
        try {
            CaptchaResult result = extractCaptchaText(Path.of(imagePath));

            System.out.println("═══════════════════════════════════════");
            System.out.println("Image        : " + imagePath);
            System.out.println("Model Used   : " + result.modelUsed());
            System.out.println("Extracted    : " + result.captchaText());
            System.out.println("═══════════════════════════════════════");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "CAPTCHA extraction failed after all retries and fallbacks.", e);
            System.exit(1);
        }
    }
    public static CaptchaResult extractCaptchaText(Path imagePath) throws Exception {
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String mediaType  = detectMediaType(imagePath);
        return extractCaptchaText(imageBytes, mediaType);
    }

    public static CaptchaResult extractCaptchaText(byte[] imageBytes, String mediaType)
            throws Exception {

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        Exception lastException = null;

        for (String model : MODEL_FALLBACK_CHAIN) {
            try {
                LOGGER.info("Attempting model: " + model);
                JSONObject rawResponse = callWithRetry(model, base64Image, mediaType);
                String text = parseOutputText(rawResponse).strip();
                return new CaptchaResult(text, model);

            } catch (NonRetryableException e) {
                lastException = e;
                LOGGER.warning("Model '" + model + "' non-retryable error: "
                        + e.getMessage() + " — trying next fallback...");
            }
        }

        throw new RuntimeException(
                "All models in fallback chain exhausted: " + MODEL_FALLBACK_CHAIN, lastException);
    }
    
    private static JSONObject callWithRetry(String model, String base64Image, String mediaType)
            throws Exception {

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return callApi(model, base64Image, mediaType);

            } catch (RateLimitException e) {
                lastException = e;
                long wait = Math.min(
                        e.retryAfterMs > 0 ? e.retryAfterMs : backoff(attempt),
                        MAX_BACKOFF_MS);
                LOGGER.warning("Rate-limited on '" + model + "'. Waiting " + wait
                        + "ms [retry " + attempt + "/" + MAX_RETRIES + "]");
                Thread.sleep(wait);

            } catch (NonRetryableException e) {
                throw e;

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

    private static JSONObject callApi(String model, String base64Image, String mediaType)
            throws IOException {

        JSONObject body = buildRequestBody(model, base64Image, mediaType);

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            handleHttpErrors(response);
            return new JSONObject(requireBody(response));
        }
    }

    private static JSONObject buildRequestBody(String model, String base64Image, String mediaType) {
        String dataUrl = "data:" + mediaType + ";base64," + base64Image;

        JSONObject imageBlock = new JSONObject()
                .put("type", "input_image")
                .put("image_url", dataUrl);

        JSONObject textBlock = new JSONObject()
                .put("type", "input_text")
                .put("text", buildPrompt());

        JSONObject userMessage = new JSONObject()
                .put("role", "user")
                .put("content", new JSONArray().put(imageBlock).put(textBlock));

        return new JSONObject()
                .put("model", model)
                .put("input", new JSONArray().put(userMessage));
    }

    private static String buildPrompt() {
        return "You are given a CAPTCHA image.\r\n"
        		+ "\r\n"
        		+ "You MUST follow a strict 2-step process internally before producing the final answer.\r\n"
        		+ "\r\n"
        		+ "-------------------------\r\n"
        		+ "STEP 1: CHARACTER DETECTION\r\n"
        		+ "-------------------------\r\n"
        		+ "- Identify ALL visible characters in the image.\r\n"
        		+ "- For each character, determine its approximate horizontal position (X-coordinate).\r\n"
        		+ "- Assign a relative X position (for example: from leftmost to rightmost).\r\n"
        		+ "- Ignore vertical position (Y-axis) completely.\r\n"
        		+ "- Do NOT try to read the CAPTCHA yet.\r\n"
        		+ "- Do NOT order characters in this step based on appearance.\r\n"
        		+ "\r\n"
        		+ "-------------------------\r\n"
        		+ "STEP 2: LEFT-TO-RIGHT ORDERING\r\n"
        		+ "-------------------------\r\n"
        		+ "- Sort all detected characters strictly based on their X-coordinate from LEFT to RIGHT.\r\n"
        		+ "- This ordering is MANDATORY and must override any visual stacking or height differences.\r\n"
        		+ "- Even if characters appear above or below each other, they still belong to the same row.\r\n"
        		+ "- There is ONLY ONE ROW.\r\n"
        		+ "\r\n"
        		+ "-------------------------\r\n"
        		+ "FINAL OUTPUT RULES\r\n"
        		+ "-------------------------\r\n"
        		+ "- After sorting, concatenate the characters into a single string.\r\n"
        		+ "- Return ONLY the final CAPTCHA text.\r\n"
        		+ "- Do NOT include spaces unless clearly present.\r\n"
        		+ "- Preserve exact capitalization.\r\n"
        		+ "- Do NOT explain anything.\r\n"
        		+ "- Do NOT add punctuation.\r\n"
        		+ "- Do NOT wrap in quotes.\r\n"
        		+ "- If any character is unclear, replace it with '?'.\r\n"
        		+ "\r\n"
        		+ "-------------------------\r\n"
        		+ "CRITICAL ENFORCEMENT\r\n"
        		+ "-------------------------\r\n"
        		+ "- NEVER read top-to-bottom.\r\n"
        		+ "- NEVER group by vertical alignment.\r\n"
        		+ "- ONLY horizontal (left-to-right) order matters.";
    }

    private static String parseOutputText(JSONObject apiResponse) throws IOException {
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
                    return block.getString("text").strip();
                }
            }
        }

        throw new IOException("No 'output_text' block found in response: " + apiResponse);
    }

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
        throw new NonRetryableException("Client error " + code + ": " + errorBody);
    }

    private static String requireBody(Response response) throws IOException {
        if (response.body() == null) throw new IOException("Empty response body.");
        return response.body().string();
    }

    private static void validateApiKey() {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IllegalStateException(
                    "API_KEY is not set. Populate the API_KEY constant before running.");
        }
    }

    private static String detectMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".gif"))  return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg"; // default for .jpg / .jpeg and unknowns
    }

    private static long backoff(int attempt) {
        long exp    = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = (long) (Math.random() * 500);
        return exp + jitter;
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

    public static final class CaptchaResult {
        private final String captchaText;
        private final String modelUsed;

        public CaptchaResult(String captchaText, String modelUsed) {
            this.captchaText = captchaText;
            this.modelUsed   = modelUsed;
        }

        public String captchaText() { return captchaText; }
        public String modelUsed()   { return modelUsed;   }

        @Override
        public String toString() {
            return "CaptchaResult{text='" + captchaText + "', model='" + modelUsed + "'}";
        }
    }
    public static void installPackages() throws Exception {

        ProcessBuilder pb = new ProcessBuilder(
                "python", "-m", "pip", "install",
                "opencv-python", "numpy", "pillow"
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        );

        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println("[pip] " + line);
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("pip install failed with exit code: " + exitCode);
        }

        System.out.println("✅ Packages installed successfully");
    }
}
