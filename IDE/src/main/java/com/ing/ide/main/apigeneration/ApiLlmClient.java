package com.ing.ide.main.apigeneration;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal one-shot text-in / text-out wrapper around OpenRouter's chat API.
 * Used for Mode 2 (OpenAPI spec → test steps) where no tool-calling loop is needed.
 */
public final class ApiLlmClient {

    private static final String OPENROUTER_URL =
            "https://openrouter.ai/api/v1/chat/completions";

    private final HttpClient http   = HttpClient.newHttpClient();
    private final String     apiKey;
    private final String     model;

    /** Raw body of the last HTTP response — exposed for diagnostics. */
    private String lastRawResponse = "";

    public ApiLlmClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model  = model;
    }

    public String getLastRawResponse() { return lastRawResponse; }

    /**
     * Sends a single system + user message pair and returns the assistant's text.
     * Handles model variants that put the answer in {@code reasoning_content} or
     * {@code thinking} instead of the standard {@code content} field.
     */
    public String call(String systemPrompt, String userMessage) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", systemPrompt));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", userMessage));

        JSONObject body = new JSONObject();
        body.put("model",      model);
        body.put("messages",   messages);
        body.put("max_tokens", 8192);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type",  "application/json")
                .header("HTTP-Referer",  "https://github.com/ing-bank/INGenious")
                .header("X-Title",       "INGenious AI Test Generator")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp =
                http.send(req, HttpResponse.BodyHandlers.ofString());

        lastRawResponse = resp.body();

        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                    "OpenRouter HTTP " + resp.statusCode() + ": " + snippet(lastRawResponse));
        }

        String clean = lastRawResponse.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        JSONObject root    = new JSONObject(clean);
        JSONObject message = root.getJSONArray("choices")
                                 .getJSONObject(0)
                                 .getJSONObject("message");

        // Standard field
        String content = message.optString("content", "").trim();

        // Some reasoning/thinking models (StepFun, DeepSeek, etc.) emit the
        // answer in an extra field when content is empty
        if (content.isEmpty()) {
            content = message.optString("reasoning_content", "").trim();
        }
        if (content.isEmpty()) {
            content = message.optString("thinking", "").trim();
        }
        if (content.isEmpty()) {
            // Try finish_reason to give a more helpful error
            String finish = root.getJSONArray("choices")
                                .getJSONObject(0)
                                .optString("finish_reason", "unknown");
            throw new RuntimeException(
                    "Model returned empty content (finish_reason=" + finish + "). "
                    + "Raw response: " + snippet(lastRawResponse));
        }

        return content;
    }

    private static String snippet(String s) {
        if (s == null) return "(null)";
        return s.length() > 800 ? s.substring(0, 800) + "..." : s;
    }
}
