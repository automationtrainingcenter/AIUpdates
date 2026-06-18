package com.ing.ide.main.playwrightrecording;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioServerParameters;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AITestGenerationAgent {

    private static final String APP_NAME = "INGenious";

    /** A single test case script extracted from the agent output. */
    public static final class TestScript {
        public final String name;
        public final String code;
        public TestScript(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }

    /**
     * A scenario group: one scenario folder containing multiple individual test cases.
     * Maps to one TestPlan sub-folder in INGenious with N CSV files inside.
     * Reusable groups (marked [REUSABLE] in LLM output) represent shared setup flows
     * like login that are referenced by other test cases.
     */
    public static final class ScenarioGroup {
        public final String scenarioName;
        public final List<TestScript> testCases;
        public final boolean reusable;
        public ScenarioGroup(String scenarioName, List<TestScript> testCases) {
            this(scenarioName, testCases, false);
        }
        public ScenarioGroup(String scenarioName, List<TestScript> testCases, boolean reusable) {
            this.scenarioName = scenarioName;
            this.testCases = testCases;
            this.reusable = reusable;
        }
    }

    // Concise system prompt - stable content improves prompt caching hit rates.
    private static final String SYSTEM_PROMPT = """
            You are a browser automation agent for INGenious Playwright Studio.

            === TOOLS TO USE - exact parameter names ===
            browser_navigate : url="https://example.com"
            browser_snapshot : (no parameters)
            browser_type     : target="#css-id"  text="value to type"
            browser_click    : target="#css-id"

            Use browser_snapshot after EVERY navigation and after each action to confirm the page state.

            STRICTLY FORBIDDEN - never call these tools under any circumstances:
            - browser_run_code_unsafe  (always fails with SyntaxError)
            - browser_fill_form        (always fails with schema validation errors)

            === LOCATOR RULES - critical for correct import ===
            ONLY use ONE of these two locator forms:
            1. #id selector:              page.locator("#user-name").fill("value")
            2. [data-test] selector:      page.locator("[data-test=\\"login-button\\"]").click()

            NEVER use CSS class selectors (.shopping_cart_link, .btn, etc.) - they cause import failures.
            Always confirm the exact id or data-test attribute from the browser_snapshot before using it.

            === WORKFLOW ===
            1. browser_navigate to the URL
            2. browser_snapshot - read the page structure, find #id or data-test attributes
            3. browser_type / browser_click for each interaction
            4. browser_snapshot after each major action to confirm navigation/state
            5. After ALL interactions complete, output ONLY the code blocks below

            === REUSABLE LOGIN ===
            A [REUSABLE] scenario holds ONLY the shared login/setup steps - EXACTLY 1 test case.
            Do NOT put functional test cases from the requirement inside [REUSABLE].
            ALL functional test cases described in the requirement go in a SEPARATE regular scenario (no [REUSABLE] suffix).
            Functional test cases must NOT include login steps (navigate to login page, fill credentials, click login).
            An Execute step calling the Login reusable is added automatically at import time - do not duplicate it.
            Each functional test case starts directly from the first action that requires an already-logged-in session.
            Do NOT use Java Runnable, lambda, method references, or helper variables - only page.* calls.

            === OUTPUT FORMAT ===
            Step 1 - Reusable scenario (output FIRST, contains ONLY login steps):

            =====SCENARIO_START: Login [REUSABLE]=====
            import com.microsoft.playwright.*;
            import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
            public class GeneratedTest {
                public static void main(String[] args) {
                    try (Playwright playwright = Playwright.create()) {
                        Browser browser = playwright.chromium().launch();
                        BrowserContext context = browser.newContext();
                        Page page = context.newPage();
                        // --- Test Case 1: Login ---
                        page.navigate("https://example.com");
                        page.locator("#username").fill("user");
                        page.locator("#password").fill("pass");
                        page.locator("#login-button").click();
                    }
                }
            }
            =====SCENARIO_END=====

            Step 2 - Regular scenario (functional test cases only - NO login steps):

            =====SCENARIO_START: <scenario name>=====
            import com.microsoft.playwright.*;
            import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
            public class GeneratedTest {
                public static void main(String[] args) {
                    try (Playwright playwright = Playwright.create()) {
                        Browser browser = playwright.chromium().launch();
                        BrowserContext context = browser.newContext();
                        Page page = context.newPage();
                        // --- Test Case 1: <name> ---
                        // Start directly from the post-login action (NO login steps here)
                        page.navigate("https://example.com/target-page");
                        page.locator("#id").click();
                    }
                }
            }
            =====SCENARIO_END=====

            === RULES ===
            - [REUSABLE] scenario: contains ONLY the login test case. Never contains functional test cases.
            - Regular scenario: contains ONLY the functional test cases described in the requirement.
            - For a single described flow: 1 regular scenario with 1 test case.
            - ONLY page.navigate(), page.locator("#id").fill(), page.locator("#id").click() - NO other Java
            - Assertions: ONLY assertThat(page.locator("#id")).isVisible() | .containsText("") | .hasValue("")
            - No markdown, no explanations, no text outside the scenario blocks
            """;

    private final String openRouterKey;
    private final String modelName;
    private final String npxCommand;
    private InMemoryRunner runner;

    public AITestGenerationAgent(String openRouterKey, String modelName, String npxCommand) {
        this.openRouterKey = openRouterKey;
        this.modelName = modelName;
        this.npxCommand = npxCommand;
    }

    public Flowable<Event> generateFromPrompt(String prompt, String appUrl,
                                               List<String> existingReusables) throws Exception {
        McpToolset playwrightMcp = buildPlaywrightMcp();
        LlmAgent agent = LlmAgent.builder()
                .name("ingenious_prompt_generator")
                .model(new OpenRouterLlm(modelName, openRouterKey))
                .instruction(SYSTEM_PROMPT)
                .tools(playwrightMcp)
                .build();
        runner = new InMemoryRunner(agent, APP_NAME);

        String userMessage = "Navigate to: " + appUrl
                + "\n\nRequirement:\n" + prompt
                + "\n\nGenerate EXACTLY the number of test cases described. For a single described flow, output 1 scenario with 1 test case."
                + " For each scenario: use browser tools to execute all steps, then output the"
                + " =====SCENARIO_START/END===== block with each test case separated by // --- Test Case N: name --- comments."
                + " Each test case starts with page.navigate()."
                + buildExistingReusablesNote(existingReusables);
        Content content = Content.fromParts(Part.fromText(userMessage));
        return runner.runAsync("user", "session-" + System.currentTimeMillis(),
                content, RunConfig.builder().autoCreateSession(true).build())
                .subscribeOn(Schedulers.io());
    }

    public Flowable<Event> generateFromAzure(
            String azureUrl, String azurePat, String azureProject,
            String testPlanId, String appUrl, List<String> existingReusables) throws Exception {

        McpToolset playwrightMcp = buildPlaywrightMcp();
        McpToolset azureMcp = buildAzureMcp(azureUrl, azurePat, azureProject);
        LlmAgent agent = LlmAgent.builder()
                .name("ingenious_azure_generator")
                .model(new OpenRouterLlm(modelName, openRouterKey))
                .instruction(SYSTEM_PROMPT)
                .tools(azureMcp, playwrightMcp)
                .build();
        runner = new InMemoryRunner(agent, APP_NAME);

        String userMessage = "Fetch ALL test cases from Azure DevOps Test Plan ID: " + testPlanId
                + "\nApp URL: " + appUrl
                + "\n\nGroup Azure test cases into scenario groups by area/category."
                + " For each group: use browser tools to execute each test case, then output the"
                + " =====SCENARIO_START/END===== block with each test case separated by // --- Test Case N: name --- comments."
                + " Each test case starts with page.navigate(). Do not skip any Azure test cases."
                + buildExistingReusablesNote(existingReusables);
        Content content = Content.fromParts(Part.fromText(userMessage));
        return runner.runAsync("user", "session-" + System.currentTimeMillis(),
                content, RunConfig.builder().autoCreateSession(true).build())
                .subscribeOn(Schedulers.io());
    }

    /**
     * Builds the "existing reusables" note appended to the user message.
     *
     * When the project already contains reusable scenarios (e.g. Login was generated
     * in a previous run), the agent must NOT regenerate them. Instead it should
     * perform login/setup for browser exploration but omit those steps from the output,
     * starting each new test case at the post-login URL directly.
     */
    private static String buildExistingReusablesNote(List<String> existingReusables) {
        if (existingReusables == null || existingReusables.isEmpty()) return "";
        String names = String.join(", ", existingReusables);
        return "\n\nIMPORTANT - these reusable scenarios already exist in the project: " + names + "."
                + " Do NOT output a [REUSABLE] scenario block for any of them."
                + " You may still navigate and perform login/setup steps in the BROWSER during exploration,"
                + " but do NOT include those steps in the generated code."
                + " Each new test case must start with page.navigate() to the post-login or feature URL directly.";
    }

    public void cancel() {
        if (runner != null) {
            try { runner.close().blockingAwait(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Extraction
    // -------------------------------------------------------------------------

    /**
     * Parses agent output into a list of ScenarioGroups.
     * Each =====SCENARIO_START/END===== block becomes one group,
     * and is further split at "// --- Test Case N: name ---" comments
     * into individual TestScript objects.
     */
    public static List<ScenarioGroup> extractScenarioGroups(String rawOutput) {
        List<ScenarioGroup> groups = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return groups;

        Pattern p = Pattern.compile(
                "={5}SCENARIO_START: ?(.+?)={5}(.*?)={5}SCENARIO_END={5}",
                Pattern.DOTALL);
        Matcher m = p.matcher(rawOutput);
        while (m.find()) {
            String rawName = m.group(1).trim();
            boolean reusable = rawName.toUpperCase().contains("[REUSABLE]");
            String scenarioName = sanitizeName(
                    rawName.replaceAll("(?i)\\s*\\[REUSABLE]\\s*", "").trim());
            String block = m.group(2).trim();
            int start = block.indexOf("import com.microsoft.playwright.*;");
            if (start == -1) continue;
            String code = block.substring(start);
            int fence = code.lastIndexOf("```");
            if (fence != -1) code = code.substring(0, fence).trim();

            List<TestScript> testCases = splitIntoTestCases(code);
            groups.add(new ScenarioGroup(scenarioName, testCases, reusable));
        }

        // Fallback: no SCENARIO delimiters - treat each old TESTCASE block or whole output as a single-TC scenario
        if (groups.isEmpty()) {
            List<TestScript> scripts = extractMultipleScripts(rawOutput);
            for (TestScript s : scripts) {
                groups.add(new ScenarioGroup(s.name, List.of(s)));
            }
        }
        return groups;
    }

    /**
     * Splits a Playwright class (one scenario) into individual TestScript objects
     * by locating "// --- Test Case N: name ---" comment markers.
     * Each segment is wrapped in its own minimal Playwright class.
     */
    public static List<TestScript> splitIntoTestCases(String scenarioCode) {
        List<TestScript> cases = new ArrayList<>();
        if (scenarioCode == null || scenarioCode.isBlank()) return cases;

        Pattern marker = Pattern.compile("//\\s*-{2,}\\s*[Tt]est\\s+[Cc]ase\\s*\\d*:?\\s*(.+?)\\s*-*\\s*$");
        String[] lines = scenarioCode.split("\n");

        String currentName = null;
        List<String> currentBody = new ArrayList<>();

        for (String line : lines) {
            Matcher mm = marker.matcher(line.trim());
            if (mm.find()) {
                // Flush previous test case
                if (currentName != null && !currentBody.isEmpty()) {
                    cases.add(new TestScript(sanitizeName(currentName), wrapInPlaywrightClass(currentBody)));
                }
                currentName = mm.group(1).trim();
                currentBody = new ArrayList<>();
            } else if (currentName != null) {
                String t = line.trim();
                if (isParserCompatibleLine(t)) {
                    currentBody.add(t);
                }
            }
        }
        // Flush last test case
        if (currentName != null && !currentBody.isEmpty()) {
            cases.add(new TestScript(sanitizeName(currentName), wrapInPlaywrightClass(currentBody)));
        }

        // No markers - treat whole scenario as a single test case
        if (cases.isEmpty()) {
            List<String> allLines = Arrays.stream(scenarioCode.split("\n"))
                    .map(String::trim)
                    .filter(AITestGenerationAgent::isParserCompatibleLine)
                    .collect(Collectors.toList());
            // Only create a test case if there are actual page.* steps to import.
            // An empty body would produce a Refactor_Object row with no element info.
            if (!allLines.isEmpty()) {
                cases.add(new TestScript("TestCase_1", wrapInPlaywrightClass(allLines)));
            }
        }
        return cases;
    }

    /**
     * Returns true only for lines the PlaywrightRecordingParser can handle.
     *
     * Only page.navigate(), page.locator(...).fill(), and page.locator(...).click()
     * are understood by the parser. Everything else - Java scaffolding, comments,
     * assertions, helper variable calls like login.run(), Runnable declarations,
     * browser. *context.* calls - causes Refactor_Object if passed through.
     *
     * Additionally, page.screenshot() is excluded because the parser has no
     * "screenshot" action mapping.
     */
    private static boolean isParserCompatibleLine(String t) {
        if (t.isEmpty()) return false;
        if (!t.startsWith("page.")) return false;
        // page.screenshot() has no parser mapping - causes Refactor_Object
        if (t.startsWith("page.screenshot")) return false;
        // Chained locators (.first(), .last(), .nth(), .filter()) can't be resolved
        // by PlaywrightRecordingParser's locator extraction - would produce Refactor_Object
        if (t.contains(").first()") || t.contains(").last()")
                || t.contains(").nth(") || t.contains(").filter(")) return false;
        return true;
    }

    /** Wraps extracted action lines in a complete Playwright Java class. */
    private static String wrapInPlaywrightClass(List<String> bodyLines) {
        return "import com.microsoft.playwright.*;\n"
             + "import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;\n"
             + "public class GeneratedTest {\n"
             + "    public static void main(String[] args) {\n"
             + "        try (Playwright playwright = Playwright.create()) {\n"
             + "            Browser browser = playwright.chromium().launch();\n"
             + "            BrowserContext context = browser.newContext();\n"
             + "            Page page = context.newPage();\n"
             + bodyLines.stream().map(l -> "            " + l).collect(Collectors.joining("\n"))
             + "\n        }\n    }\n}\n";
    }

    // -------------------------------------------------------------------------
    // Legacy helpers (kept for fallback / backward compatibility)
    // -------------------------------------------------------------------------

    public static List<TestScript> extractMultipleScripts(String rawOutput) {
        List<TestScript> results = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return results;

        Pattern p = Pattern.compile(
                "={5}SCENARIO_START: ?(.+?)={5}(.*?)={5}SCENARIO_END={5}",
                Pattern.DOTALL);
        Matcher m = p.matcher(rawOutput);
        while (m.find()) {
            String name = m.group(1).trim();
            String block = m.group(2).trim();
            int start = block.indexOf("import com.microsoft.playwright.*;");
            if (start != -1) {
                String code = block.substring(start);
                int fence = code.lastIndexOf("```");
                if (fence != -1) code = code.substring(0, fence).trim();
                results.add(new TestScript(sanitizeName(name), removeBlankLines(code)));
            }
        }
        if (results.isEmpty()) {
            String code = extractPlaywrightCode(rawOutput);
            if (!code.isBlank()) results.add(new TestScript("GeneratedTest", code));
        }
        return results;
    }

    public static String extractPlaywrightCode(String rawOutput) {
        if (rawOutput == null) return "";
        int start = rawOutput.indexOf("import com.microsoft.playwright.*;");
        if (start == -1) return rawOutput.trim();
        String code = rawOutput.substring(start);
        int fence = code.lastIndexOf("```");
        if (fence != -1) code = code.substring(0, fence).trim();
        return removeBlankLines(code);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String removeBlankLines(String code) {
        return code.lines()
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^A-Za-z0-9_\\-]", "_").replaceAll("_+", "_");
    }

    private McpToolset buildPlaywrightMcp() {
        StdioServerParameters params = StdioServerParameters.builder()
                .command(npxCommand)
                .args(Arrays.asList("@playwright/mcp@latest"))
                .env(System.getenv())
                .build();
        return new McpToolset(params.toServerParameters());
    }

    private McpToolset buildAzureMcp(String azureUrl, String azurePat, String project) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("AZURE_DEVOPS_ORG_URL", azureUrl);
        env.put("AZURE_PERSONAL_ACCESS_TOKEN", azurePat);
        env.put("AZURE_DEVOPS_PROJECT", project);
        StdioServerParameters params = StdioServerParameters.builder()
                .command(npxCommand)
                .args(Arrays.asList("azure-devops-mcp"))
                .env(env)
                .build();
        return new McpToolset(params.toServerParameters());
    }
}
