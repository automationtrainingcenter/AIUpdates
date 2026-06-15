package com.ing.ide.main.apigeneration;

import com.ing.datalib.api.APIAssertion;
import com.ing.datalib.api.APICollection;
import com.ing.datalib.api.APIRequest;
import com.ing.datalib.api.AuthConfig;
import com.ing.datalib.api.KeyValuePair;
import java.util.ArrayList;
import java.util.List;

/**
 * Mode 1 — deterministic conversion from a saved {@link APICollection} into
 * {@link ApiTestScenario} objects.  No LLM involved.
 *
 * Each {@link APIRequest} in the collection becomes one test case whose steps
 * follow the standard INGenious Webservice action sequence:
 *   setEndPoint → addHeader(s) → addURLParam(s) → [HTTP verb] → assert(s) → closeConnection
 */
public class ApiTestCaseGenerator {

    private ApiTestCaseGenerator() {}

    /**
     * Converts every request in {@code collection} (including nested folders)
     * into one {@link ApiTestScenario}.
     */
    public static List<ApiTestScenario> fromCollection(APICollection collection) {
        List<ApiTestCase> testCases = new ArrayList<>();
        for (APIRequest request : collection.getAllRequests()) {
            testCases.add(buildTestCase(request));
        }
        return List.of(new ApiTestScenario(sanitize(collection.getName()), testCases));
    }

    // -------------------------------------------------------------------------
    // Test case builder
    // -------------------------------------------------------------------------

    private static ApiTestCase buildTestCase(APIRequest request) {
        List<ApiTestRow> steps = new ArrayList<>();

        // 1. setEndPoint
        steps.add(new ApiTestRow("setEndPoint", nvl(request.getUrl()), "",
                "Set endpoint"));

        // 2. Auth → translate to addHeader / addURLParam steps
        addAuthSteps(steps, request.getAuth());

        // 3. Explicit headers (enabled only)
        for (KeyValuePair h : request.getEnabledHeaders()) {
            steps.add(new ApiTestRow("addHeader",
                    h.getKey() + "=" + nvl(h.getValue()), "",
                    "Header: " + h.getKey()));
        }

        // 4. Query params (enabled only)
        for (KeyValuePair p : request.getEnabledQueryParams()) {
            steps.add(new ApiTestRow("addURLParam",
                    p.getKey() + "=" + nvl(p.getValue()), "",
                    "Param: " + p.getKey()));
        }

        // 5. HTTP method + body
        String body = "";
        if (request.getBody() != null && request.getBody().getRawContent() != null) {
            body = request.getBody().getRawContent();
        }
        steps.add(new ApiTestRow(toActionName(request.getMethod()), body, "",
                request.getMethod().name() + " request"));

        // 6. assertResponseCode 200 — unless the request already has a status assertion
        boolean hasStatusAssertion = request.getAssertions().stream()
                .anyMatch(a -> a.isEnabled()
                        && a.getType() == APIAssertion.AssertionType.STATUS_CODE);
        if (!hasStatusAssertion) {
            steps.add(new ApiTestRow("assertResponseCode", "200", "",
                    "Assert HTTP 200"));
        }

        // 7. Workbench assertions → INGenious assert actions
        for (APIAssertion assertion : request.getAssertions()) {
            if (!assertion.isEnabled()) continue;
            ApiTestRow row = toAssertionRow(assertion);
            if (row != null) steps.add(row);
        }

        // 8. closeConnection
        steps.add(new ApiTestRow("closeConnection", "", "", "Close connection"));

        return new ApiTestCase(sanitize(nvl(request.getName())), steps);
    }

    // -------------------------------------------------------------------------
    // Auth → step mapping
    // -------------------------------------------------------------------------

    private static void addAuthSteps(List<ApiTestRow> steps, AuthConfig auth) {
        if (auth == null || auth.getAuthType() == null
                || auth.getAuthType() == AuthConfig.AuthType.NONE) {
            return;
        }
        switch (auth.getAuthType()) {
            case BEARER -> {
                String authHeader = auth.getAuthorizationHeader();
                if (authHeader != null) {
                    steps.add(new ApiTestRow("addHeader",
                            "Authorization=" + authHeader, "", "Bearer auth"));
                }
            }
            case BASIC -> {
                String authHeader = auth.getAuthorizationHeader();
                if (authHeader != null) {
                    steps.add(new ApiTestRow("addHeader",
                            "Authorization=" + authHeader, "", "Basic auth"));
                }
            }
            case API_KEY -> {
                KeyValuePair hdr = auth.getApiKeyHeader();
                if (hdr != null) {
                    steps.add(new ApiTestRow("addHeader",
                            hdr.getKey() + "=" + hdr.getValue(), "", "API key header"));
                }
                KeyValuePair param = auth.getApiKeyQueryParam();
                if (param != null) {
                    steps.add(new ApiTestRow("addURLParam",
                            param.getKey() + "=" + param.getValue(), "", "API key param"));
                }
            }
            case OAUTH2 -> {
                String authHeader = auth.getAuthorizationHeader();
                if (authHeader != null) {
                    steps.add(new ApiTestRow("addHeader",
                            "Authorization=" + authHeader, "", "OAuth2 bearer"));
                }
            }
            default -> { /* NONE or unknown */ }
        }
    }

    // -------------------------------------------------------------------------
    // HTTP method → action name
    // -------------------------------------------------------------------------

    private static String toActionName(APIRequest.HttpMethod method) {
        if (method == null) return "getRestRequest";
        return switch (method) {
            case GET    -> "getRestRequest";
            case POST   -> "postRestRequest";
            case PUT    -> "putRestRequest";
            case PATCH  -> "patchRestRequest";
            case DELETE -> "deleteRestRequest";
            default     -> "getRestRequest";
        };
    }

    // -------------------------------------------------------------------------
    // Workbench assertion → INGenious assert action
    // -------------------------------------------------------------------------

    private static ApiTestRow toAssertionRow(APIAssertion a) {
        if (a.getType() == null) return null;
        String target   = nvl(a.getTarget());
        String expected = nvl(a.getExpectedValue());
        String op       = a.getOperator() != null ? a.getOperator().name() : "EQUALS";

        return switch (a.getType()) {
            case STATUS_CODE  -> new ApiTestRow("assertResponseCode", expected, "",
                    "Assert status: " + expected);
            case JSON_PATH    -> switch (op) {
                case "EQUALS"       -> new ApiTestRow("assertJSONelementEquals",
                        expected, target, "JSON " + target + " = " + expected);
                case "NOT_EQUALS"   -> new ApiTestRow("assertJSONelementNotEquals",
                        expected, target, "JSON " + target + " != " + expected);
                case "CONTAINS"     -> new ApiTestRow("assertJSONelementContains",
                        expected, target, "JSON " + target + " contains " + expected);
                case "NOT_CONTAINS" -> new ApiTestRow("assertJSONelementNotContains",
                        expected, target, "JSON " + target + " not contains " + expected);
                default -> null;
            };
            case XPATH        -> switch (op) {
                case "EQUALS"   -> new ApiTestRow("assertXMLelementEquals",
                        expected, target, "XML " + target + " = " + expected);
                case "CONTAINS" -> new ApiTestRow("assertXMLelementContains",
                        expected, target, "XML " + target + " contains " + expected);
                default -> null;
            };
            case HEADER       -> switch (op) {
                case "EQUALS"   -> new ApiTestRow("assertHeaderValueEquals",
                        expected, target, "Header " + target + " = " + expected);
                case "CONTAINS" -> new ApiTestRow("assertHeaderValueContains",
                        expected, target, "Header " + target + " contains " + expected);
                default -> null;
            };
            case BODY_CONTAINS -> new ApiTestRow("assertResponsebodycontains",
                    expected, "", "Body contains: " + expected);
            default -> null;
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "API_Test";
        return name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
