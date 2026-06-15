package com.ing.ide.main.apigeneration;

import com.google.adk.tools.Annotations;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP tool methods exposed to the Mode 3 LLM agent via Google ADK's
 * {@code FunctionTool.create(instance, methodName)}.
 *
 * Each method is annotated with {@link Annotations.Schema} so ADK can build
 * the correct JSON function declaration for the model without needing the
 * {@code -parameters} compiler flag.
 */
public final class ApiHttpTools {

    private static final int MAX_BODY_CHARS = 4000;

    private final HttpClient http;
    private final String     baseUrl;

    public ApiHttpTools(String baseUrl) {
        this.http    = HttpClient.newHttpClient();
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public tool methods (called by the LLM via ADK)
    // ─────────────────────────────────────────────────────────────────────────

    @Annotations.Schema(name = "http_get",
            description = "Send an HTTP GET request to the API and return status + body.")
    public Map<String, Object> http_get(
            @Annotations.Schema(name = "path",
                    description = "Endpoint path, e.g. /users or /users/42")
            String path,
            @Annotations.Schema(name = "headers",
                    description = "Optional extra headers, one per line: 'Key: Value'",
                    optional = true)
            String headers) {

        return execute("GET", path, headers, null);
    }

    @Annotations.Schema(name = "http_post",
            description = "Send an HTTP POST request with a JSON body and return status + body.")
    public Map<String, Object> http_post(
            @Annotations.Schema(name = "path",
                    description = "Endpoint path, e.g. /users")
            String path,
            @Annotations.Schema(name = "body",
                    description = "JSON request body as a string")
            String body,
            @Annotations.Schema(name = "headers",
                    description = "Optional extra headers, one per line: 'Key: Value'",
                    optional = true)
            String headers) {

        return execute("POST", path, headers, body);
    }

    @Annotations.Schema(name = "http_put",
            description = "Send an HTTP PUT request with a JSON body and return status + body.")
    public Map<String, Object> http_put(
            @Annotations.Schema(name = "path",
                    description = "Endpoint path, e.g. /users/42")
            String path,
            @Annotations.Schema(name = "body",
                    description = "JSON request body as a string")
            String body,
            @Annotations.Schema(name = "headers",
                    description = "Optional extra headers, one per line: 'Key: Value'",
                    optional = true)
            String headers) {

        return execute("PUT", path, headers, body);
    }

    @Annotations.Schema(name = "http_patch",
            description = "Send an HTTP PATCH request and return status + body.")
    public Map<String, Object> http_patch(
            @Annotations.Schema(name = "path",
                    description = "Endpoint path, e.g. /users/42")
            String path,
            @Annotations.Schema(name = "body",
                    description = "JSON request body as a string")
            String body,
            @Annotations.Schema(name = "headers",
                    description = "Optional extra headers, one per line: 'Key: Value'",
                    optional = true)
            String headers) {

        return execute("PATCH", path, headers, body);
    }

    @Annotations.Schema(name = "http_delete",
            description = "Send an HTTP DELETE request and return status + body.")
    public Map<String, Object> http_delete(
            @Annotations.Schema(name = "path",
                    description = "Endpoint path, e.g. /users/42")
            String path,
            @Annotations.Schema(name = "headers",
                    description = "Optional extra headers, one per line: 'Key: Value'",
                    optional = true)
            String headers) {

        return execute("DELETE", path, headers, null);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> execute(String method, String path,
                                         String headersText, String body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String url = baseUrl + (path.startsWith("/") ? path : "/" + path);
            result.put("url",    url);
            result.put("method", method);

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");

            parseHeaders(headersText, b);

            HttpRequest.BodyPublisher publisher =
                    (body != null && !body.isBlank())
                            ? HttpRequest.BodyPublishers.ofString(body)
                            : HttpRequest.BodyPublishers.noBody();
            b.method(method, publisher);

            HttpResponse<String> resp =
                    http.send(b.build(), HttpResponse.BodyHandlers.ofString());

            result.put("status", resp.statusCode());

            String responseBody = resp.body();
            if (responseBody != null && responseBody.length() > MAX_BODY_CHARS) {
                responseBody = responseBody.substring(0, MAX_BODY_CHARS) + "...[truncated]";
            }
            result.put("body", responseBody);

        } catch (Exception ex) {
            result.put("error",  ex.getMessage());
            result.put("status", -1);
        }
        return result;
    }

    private static void parseHeaders(String headersText, HttpRequest.Builder b) {
        if (headersText == null || headersText.isBlank()) return;
        for (String line : headersText.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                b.header(line.substring(0, colon).trim(),
                         line.substring(colon + 1).trim());
            }
        }
    }
}
