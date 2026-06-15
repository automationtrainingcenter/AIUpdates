package com.ing.ide.main.playwrightrecording;

import com.google.adk.JsonBaseModel;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.chat.ChatCompletionsRequest;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Routes ADK LlmRequest through OpenRouter's OpenAI-compatible REST API.
 *
 * Uses ChatCompletionsRequest.fromLlmRequest() so that tool definitions
 * (Playwright MCP functions) are included in every API call — the model
 * can then issue real tool_calls instead of hallucinating JSON text.
 */
public class OpenRouterLlm extends BaseLlm {

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

    private final String apiKey;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public OpenRouterLlm(String modelName, String apiKey) {
        super(modelName);
        this.apiKey = apiKey;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        return Flowable.fromCallable(() -> {
            // Use ADK's built-in converter: handles messages, system prompt, AND tool definitions
            ChatCompletionsRequest chatReq = ChatCompletionsRequest.fromLlmRequest(request, false);
            chatReq.model = model(); // ensure OpenRouter model name is used

            // Serialize with ADK's Jackson mapper (correct field naming conventions)
            String json = JsonBaseModel.toJsonString(chatReq);

            // For Anthropic models: inject cache_control on the system message so the
            // stable system prompt + tool definitions are cached across tool-call iterations
            if (model().startsWith("anthropic/")) {
                json = injectAnthropicCacheControl(json);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OPENROUTER_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://github.com/ing-bank/INGenious")
                    .header("X-Title", "INGenious AI Test Generator")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new RuntimeException("OpenRouter API error " + resp.statusCode() + ": " + resp.body());
            }

            // Strip control characters (e.g. null bytes) that break JSON parsing
            String body = resp.body().replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
            JSONObject message = new JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message");

            // If the model wants to call tools, return FunctionCall parts so ADK executes them
            if (message.has("tool_calls") && !message.isNull("tool_calls")) {
                JSONArray toolCalls = message.getJSONArray("tool_calls");
                List<Part> parts = new ArrayList<>();
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject tc = toolCalls.getJSONObject(i);
                    String toolCallId = tc.optString("id", null);
                    String funcName = tc.getJSONObject("function").getString("name");
                    String argsStr = tc.getJSONObject("function").optString("arguments", "{}");
                    Map<String, Object> argsMap = new JSONObject(argsStr).toMap();
                    // Preserve the model's tool_call id so ADK can match it with the tool result
                    FunctionCall.Builder fcBuilder = FunctionCall.builder().name(funcName).args(argsMap);
                    if (toolCallId != null && !toolCallId.isBlank()) {
                        fcBuilder = fcBuilder.id(toolCallId);
                    }
                    parts.add(Part.builder().functionCall(fcBuilder.build()).build());
                }
                return LlmResponse.builder()
                        .content(Content.fromParts(parts.toArray(new Part[0])))
                        .build();
            }

            // Regular text response
            String text = "";
            if (message.has("content") && !message.isNull("content")) {
                text = message.getString("content");
            }
            return LlmResponse.builder()
                    .content(Content.fromParts(Part.fromText(text)))
                    .build();
        });
    }

    /**
     * Converts the system message content from a plain string to a content-block array
     * so Anthropic's prompt caching kicks in via the cache_control flag.
     * Without this, every tool-call iteration re-charges tokens for the full system prompt.
     */
    private static String injectAnthropicCacheControl(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray messages = root.optJSONArray("messages");
            if (messages == null) return json;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                if ("system".equals(msg.optString("role")) && msg.has("content")) {
                    Object content = msg.get("content");
                    if (content instanceof String) {
                        JSONArray block = new JSONArray();
                        JSONObject textPart = new JSONObject();
                        textPart.put("type", "text");
                        textPart.put("text", content);
                        textPart.put("cache_control", new JSONObject().put("type", "ephemeral"));
                        block.put(textPart);
                        msg.put("content", block);
                    }
                }
            }
            return root.toString();
        } catch (Exception e) {
            return json; // if anything goes wrong, use original
        }
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("Live/streaming connection mode not supported for OpenRouter");
    }
}
