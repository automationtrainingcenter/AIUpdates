package com.ing.ide.main.apigeneration;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.ing.ide.main.playwrightrecording.OpenRouterLlm;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Mode 3 — AI agent that makes REAL HTTP calls to a live API, observes the
 * actual responses, and then generates INGenious API test steps.
 *
 * Uses Google ADK {@link FunctionTool} backed by {@link ApiHttpTools} so the
 * LLM can call http_get / http_post / http_put / http_patch / http_delete
 * as native tool calls.  Output text must follow the SCENARIO/TESTCASE
 * delimiter format parsed by {@link ApiOutputParser}.
 */
public final class ApiHttpToolAgent {

    private static final String APP_NAME = "INGenious";

    private static final String SYSTEM_PROMPT = """
            You are an API test generation agent for INGenious Playwright Studio.

            Use the HTTP tools (http_get, http_post, http_put, http_patch, http_delete) to make
            REAL API calls, observe the actual responses (status codes, JSON fields, headers),
            and then generate structured INGenious test steps based on what you discover.

            ═══ WORKFLOW ═══
            1. If authentication is needed, call the login endpoint first to get a real token.
            2. Explore the API using http_get to understand available resources.
            3. Test CREATE (POST), READ (GET), UPDATE (PUT/PATCH), DELETE flows.
            4. Also test error cases: missing fields, wrong IDs, bad auth (401), not found (404).
            5. After ALL tool calls, output ONLY the delimiter blocks below — nothing else.

            ═══ OUTPUT FORMAT ═══
            =====SCENARIO_START: <scenario_name>=====
            =====TESTCASE_START: <test_case_name>=====
            action|input|condition|description
            action|input|condition|description
            =====TESTCASE_END=====
            =====SCENARIO_END=====

            ═══ AUTHENTICATION — REUSABLE PATTERN ═══
            If the API requires authentication, output an Auth reusable scenario FIRST:

            =====SCENARIO_START: Auth [REUSABLE]=====
            =====TESTCASE_START: Login=====
            setEndPoint|<full_login_url>||Set login endpoint
            addHeader|Content-Type=application/json||Set content type
            postRestRequest|<actual_login_body>||Send login request with real credentials observed
            assertResponseCode|200||Assert login successful
            storeJSONelement|<$.actual.token.path>|%authToken%|Store bearer token
            closeConnection|||End login request
            =====TESTCASE_END=====
            =====SCENARIO_END=====

            Note for storeJSONelement: input=JSONPath (from actual response), condition=%varName%

            Then start every protected test case with:
            Execute|Auth:Login||Execute reusable login
            setEndPoint|<full_url>||Set endpoint
            addHeader|Authorization=Bearer %authToken%||Add bearer token from login
            ...

            ═══ AVAILABLE ACTIONS ═══
            setEndPoint|<full_url>||Set API endpoint (always first in a test case)
            addHeader|<key>=<value>||Add request header
            addURLParam|<key>=<value>||Add URL query parameter
            getRestRequest|||Execute GET request
            postRestRequest|<json_body>||Execute POST request
            putRestRequest|<json_body>||Execute PUT request
            patchRestRequest|<json_body>||Execute PATCH request
            deleteRestRequest|||Execute DELETE request
            assertResponseCode|<code>||Assert HTTP status code
            assertJSONelementEquals|<expected>|<$.jsonpath>|Assert JSON value equals
            assertJSONelementNotEquals|<expected>|<$.jsonpath>|Assert JSON not equals
            assertJSONelementContains|<expected>|<$.jsonpath>|Assert JSON contains value
            assertResponsebodycontains|<expected>||Assert body contains text
            assertHeaderValueEquals|<expected>|<header-name>|Assert header value equals
            storeJSONelement|<$.jsonpath>|<%varName%>|Store JSON response value to variable
            Execute|<ScenarioName:TestCaseName>||Call a reusable test case
            closeConnection|||End request (always last in a test case)

            ═══ RULES ═══
            - Every test case MUST start with setEndPoint (or Execute) and end with closeConnection.
            - Use the FULL URL (baseUrl + path) in setEndPoint.
            - Use ACTUAL values from tool responses (real field names, real status codes).
            - Generate 1-3 scenarios, each with 2-5 test cases.
            - For error/unauthorized test cases, omit the Execute auth step to test unauthenticated.
            - ABSOLUTELY NO text, markdown, or explanation outside the delimiter blocks.
            """;

    private final String       apiKey;
    private final String       model;
    private InMemoryRunner     runner;

    public ApiHttpToolAgent(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model  = model;
    }

    /**
     * Starts the agent and returns a cold {@link Flowable} of ADK events.
     * Subscribe on an IO thread; text events carry the delimiter-formatted output.
     *
     * @param baseUrl   API base URL (e.g. https://api.example.com)
     * @param nlPrompt  natural-language description of what to test
     */
    public Flowable<Event> generate(String baseUrl, String nlPrompt) throws Exception {
        ApiHttpTools httpTools = new ApiHttpTools(baseUrl);

        LlmAgent agent = LlmAgent.builder()
                .name("ingenious_api_http_agent")
                .model(new OpenRouterLlm(model, apiKey))
                .instruction(SYSTEM_PROMPT)
                .tools(
                        FunctionTool.create(httpTools, "http_get"),
                        FunctionTool.create(httpTools, "http_post"),
                        FunctionTool.create(httpTools, "http_put"),
                        FunctionTool.create(httpTools, "http_patch"),
                        FunctionTool.create(httpTools, "http_delete")
                )
                .build();

        runner = new InMemoryRunner(agent, APP_NAME);

        String userMessage =
                "Base URL: " + baseUrl + "\n\n"
                + "What to test:\n" + nlPrompt + "\n\n"
                + "Use the HTTP tools to make real API calls first, then produce the "
                + "=====SCENARIO_START/END===== output blocks.";

        Content content = Content.fromParts(Part.fromText(userMessage));
        return runner.runAsync(
                        "user",
                        "api-session-" + System.currentTimeMillis(),
                        content,
                        RunConfig.builder().autoCreateSession(true).build())
                .subscribeOn(Schedulers.io());
    }

    public void cancel() {
        if (runner != null) {
            try { runner.close().blockingAwait(); } catch (Exception ignored) {}
        }
    }
}
