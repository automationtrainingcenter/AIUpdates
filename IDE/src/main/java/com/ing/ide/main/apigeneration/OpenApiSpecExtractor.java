package com.ing.ide.main.apigeneration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads an OpenAPI / Swagger spec from a URL and extracts a filtered slice.
 *
 * Two filter modes:
 *  - By tag/controller: keeps only operations whose "tags" list contains the filter value
 *  - By path:          keeps only paths whose path string contains the filter value
 *
 * After path filtering, only the schemas actually referenced (directly or
 * transitively via $ref) by the kept paths are included — the rest of
 * components/definitions is dropped to minimise token usage.
 */
@SuppressWarnings("unchecked")
public final class OpenApiSpecExtractor {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final List<String> META_KEYS =
            List.of("openapi", "swagger", "info", "servers", "host", "basePath",
                    "schemes", "consumes", "produces", "securityDefinitions", "security");

    private final ObjectMapper mapper     = new ObjectMapper();
    private final HttpClient   httpClient = HttpClient.newHttpClient();

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches the spec JSON from {@code specUrl} and returns a filtered
     * JSON string.  If {@code filterValue} is blank the full spec is returned.
     */
    public String loadAndFilter(String specUrl, boolean filterByTag, String filterValue)
            throws Exception {

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(specUrl))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                    "Failed to fetch spec from " + specUrl + " — HTTP " + resp.statusCode());
        }

        return filter(resp.body(), filterByTag, filterValue);
    }

    /**
     * Accepts a raw spec JSON string and returns a filtered JSON string.
     */
    public String filter(String specContent, boolean filterByTag, String filterValue)
            throws Exception {

        Map<String, Object> spec = mapper.readValue(specContent, Map.class);

        if (filterValue == null || filterValue.isBlank()) {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        }

        Map<String, Object> filtered = applyFilter(spec, filterByTag, filterValue.trim());
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(filtered);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> applyFilter(Map<String, Object> spec,
                                            boolean filterByTag, String filterValue) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Copy top-level metadata (info, servers, security definitions, etc.)
        for (String key : META_KEYS) {
            if (spec.containsKey(key)) result.put(key, spec.get(key));
        }

        // ── 1. Filter paths ──────────────────────────────────────────────────
        Map<String, Object> allPaths =
                (Map<String, Object>) spec.getOrDefault("paths", Map.of());
        Map<String, Object> filteredPaths = new LinkedHashMap<>();
        String lower = filterValue.toLowerCase();

        for (Map.Entry<String, Object> entry : allPaths.entrySet()) {
            String              path = entry.getKey();
            Map<String, Object> item = (Map<String, Object>) entry.getValue();

            if (filterByTag) {
                Map<String, Object> matchingOps = operationsMatchingTag(item, lower);
                if (!matchingOps.isEmpty()) {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    item.entrySet().stream()
                            .filter(e -> !isHttpMethod(e.getKey()))
                            .forEach(e -> merged.put(e.getKey(), e.getValue()));
                    merged.putAll(matchingOps);
                    filteredPaths.put(path, merged);
                }
            } else {
                if (path.toLowerCase().contains(lower)) {
                    filteredPaths.put(path, item);
                }
            }
        }
        result.put("paths", filteredPaths);

        // ── 2. Collect $ref schema names used by the filtered paths ──────────
        Map<String, Object> allSchemas    = extractSchemaMap(spec, "components", "schemas");
        Map<String, Object> allDefinitions = (Map<String, Object>)
                spec.getOrDefault("definitions", Map.of());

        // Merge both sources for recursive resolution
        Map<String, Object> combinedSchemas = new LinkedHashMap<>();
        combinedSchemas.putAll(allDefinitions);
        combinedSchemas.putAll(allSchemas);

        Set<String> referencedNames = new LinkedHashSet<>();
        collectRefs(filteredPaths, combinedSchemas, referencedNames);

        // ── 3. Copy only referenced schemas into the result ──────────────────
        if (!allSchemas.isEmpty()) {
            Map<String, Object> filteredSchemas = new LinkedHashMap<>();
            for (String name : referencedNames) {
                if (allSchemas.containsKey(name)) {
                    filteredSchemas.put(name, allSchemas.get(name));
                }
            }

            // Rebuild components: keep non-schema sections (securitySchemes, etc.)
            // but replace the schemas section with the filtered subset
            Map<String, Object> srcComponents =
                    (Map<String, Object>) spec.getOrDefault("components", Map.of());
            Map<String, Object> filteredComponents = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : srcComponents.entrySet()) {
                if (!"schemas".equals(e.getKey())) {
                    filteredComponents.put(e.getKey(), e.getValue());
                }
            }
            if (!filteredSchemas.isEmpty()) {
                filteredComponents.put("schemas", filteredSchemas);
            }
            if (!filteredComponents.isEmpty()) {
                result.put("components", filteredComponents);
            }
        }

        if (!allDefinitions.isEmpty()) {
            Map<String, Object> filteredDefs = new LinkedHashMap<>();
            for (String name : referencedNames) {
                if (allDefinitions.containsKey(name)) {
                    filteredDefs.put(name, allDefinitions.get(name));
                }
            }
            if (!filteredDefs.isEmpty()) {
                result.put("definitions", filteredDefs);
            }
        }

        return result;
    }

    /**
     * Recursively walks {@code node} and adds every schema name referenced via
     * {@code $ref} to {@code found}. Resolves each found schema from
     * {@code allSchemas} to follow transitive refs (e.g. a request body schema
     * that embeds another schema via $ref).
     */
    private void collectRefs(Object node, Map<String, Object> allSchemas, Set<String> found) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            Object refValue = map.get("$ref");
            if (refValue instanceof String) {
                String name = extractSchemaName((String) refValue);
                if (name != null && found.add(name)) {
                    // Recurse into the schema itself to pick up nested refs
                    Object schema = allSchemas.get(name);
                    if (schema != null) {
                        collectRefs(schema, allSchemas, found);
                    }
                }
            }
            for (Object value : map.values()) {
                collectRefs(value, allSchemas, found);
            }
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) {
                collectRefs(item, allSchemas, found);
            }
        }
    }

    /**
     * Extracts the schema name from a $ref string.
     * Handles {@code #/components/schemas/Name} and {@code #/definitions/Name}.
     */
    private static String extractSchemaName(String ref) {
        if (ref == null || !ref.startsWith("#/")) return null;
        String[] parts = ref.split("/");
        return parts.length >= 1 ? parts[parts.length - 1] : null;
    }

    private Map<String, Object> extractSchemaMap(Map<String, Object> spec,
                                                  String section, String subSection) {
        Object sectionObj = spec.get(section);
        if (!(sectionObj instanceof Map)) return Map.of();
        Object subObj = ((Map<String, Object>) sectionObj).get(subSection);
        if (!(subObj instanceof Map)) return Map.of();
        return (Map<String, Object>) subObj;
    }

    private Map<String, Object> operationsMatchingTag(Map<String, Object> pathItem,
                                                       String tagFilter) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : pathItem.entrySet()) {
            if (!isHttpMethod(e.getKey())) continue;
            Map<String, Object> op   = (Map<String, Object>) e.getValue();
            List<String>        tags = (List<String>) op.getOrDefault("tags", new ArrayList<>());
            boolean matches = tags.stream().anyMatch(t -> t.toLowerCase().contains(tagFilter));
            if (matches) out.put(e.getKey(), op);
        }
        return out;
    }

    private boolean isHttpMethod(String key) {
        return HTTP_METHODS.contains(key.toLowerCase());
    }
}
