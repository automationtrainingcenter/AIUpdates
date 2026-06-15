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
     */
    public static final class ScenarioGroup {
        public final String scenarioName;
        public final List<TestScript> testCases;
        public ScenarioGroup(String scenarioName, List<TestScript> testCases) {
            this.scenarioName = scenarioName;
            this.testCases = testCases;
        }
    }

    // Concise system prompt — stable content improves prompt caching hit rates.
    private static final String SYSTEM_PROMPT = """
            You are a browser automation agent. Use browser tools FIRST — never write code from memory.

            Workflow: browser_navigate → browser_screenshot → browser_click/browser_fill/etc. for every action.
            After completing ALL browser interactions for a scenario group, output its code block.

            ═══ OUTPUT FORMAT ═══
            One block per scenario group:

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
                        page.navigate("...");
                        // browser actions and assertions
                        // --- Test Case 2: <name> ---
                        page.navigate("...");
                        // browser actions and assertions
                    }
                }
            }
            =====SCENARIO_END=====

            Rules:
            - Group related test flows; each test case starts with page.navigate().
            - Generate 2–3 scenario groups, each with 2–5 test cases.
            - Assertions: ONLY assertThat(locator).isVisible() | .containsText("") | .hasValue("")
            - FORBIDDEN: expect() .toBeVisible() .toHaveText() .toHaveValue() waitForSelector()
            - Locators: any standard Playwright locator seen in the browser.
            - No markdown, no text outside the scenario blocks.
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

    public Flowable<Event> generateFromPrompt(String prompt, String appUrl) throws Exception {
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
                + "\n\nOrganise into 2–3 SCENARIO GROUPS (e.g. valid flows, invalid flows, edge cases)."
                + " For each group: use browser tools to run all test cases, then output the"
                + " =====SCENARIO_START/END===== block with each test case separated by // --- Test Case N: name --- comments."
                + " Each test case starts with page.navigate().";
        Content content = Content.fromParts(Part.fromText(userMessage));
        return runner.runAsync("user", "session-" + System.currentTimeMillis(),
                content, RunConfig.builder().autoCreateSession(true).build())
                .subscribeOn(Schedulers.io());
    }

    public Flowable<Event> generateFromAzure(
            String azureUrl, String azurePat, String azureProject,
            String testPlanId, String appUrl) throws Exception {

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
                + " Each test case starts with page.navigate(). Do not skip any Azure test cases.";
        Content content = Content.fromParts(Part.fromText(userMessage));
        return runner.runAsync("user", "session-" + System.currentTimeMillis(),
                content, RunConfig.builder().autoCreateSession(true).build())
                .subscribeOn(Schedulers.io());
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
            String scenarioName = sanitizeName(m.group(1).trim());
            String block = m.group(2).trim();
            int start = block.indexOf("import com.microsoft.playwright.*;");
            if (start == -1) continue;
            String code = block.substring(start);
            int fence = code.lastIndexOf("```");
            if (fence != -1) code = code.substring(0, fence).trim();

            List<TestScript> testCases = splitIntoTestCases(code);
            groups.add(new ScenarioGroup(scenarioName, testCases));
        }

        // Fallback: no SCENARIO delimiters — treat each old TESTCASE block or whole output as a single-TC scenario
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

        // No markers — treat whole scenario as a single test case
        if (cases.isEmpty()) {
            List<String> allLines = Arrays.stream(scenarioCode.split("\n"))
                    .map(String::trim)
                    .filter(AITestGenerationAgent::isParserCompatibleLine)
                    .collect(Collectors.toList());
            cases.add(new TestScript("TestCase_1", wrapInPlaywrightClass(allLines)));
        }
        return cases;
    }

    /**
     * Returns true only for lines the PlaywrightRecordingParser can handle.
     * Filters out: blank lines, Java scaffolding, comments, assertions,
     * screenshots, and non-page browser calls — all of which cause Refactor_Object.
     */
    private static boolean isParserCompatibleLine(String t) {
        if (t.isEmpty()) return false;
        // Java class/method scaffolding
        if (t.startsWith("import ")) return false;
        if (t.startsWith("public class ")) return false;
        if (t.startsWith("public static void main")) return false;
        if (t.startsWith("try (Playwright")) return false;
        if (t.startsWith("Browser ")) return false;
        if (t.startsWith("BrowserContext ")) return false;
        if (t.startsWith("Page ")) return false;
        if (t.equals("{") || t.equals("}")) return false;
        // Comments → Refactor_Object in parser
        if (t.startsWith("//")) return false;
        // Assertions → not understood by parser → Refactor_Object
        if (t.startsWith("assertThat")) return false;
        // Screenshots → parser doesn't know "screenshot" action → Refactor_Object
        if (t.startsWith("page.screenshot")) return false;
        // browser.close() and other browser-level calls → processed after playwrightSteps≥1 → Refactor_Object
        if (t.startsWith("browser.")) return false;
        if (t.startsWith("context.")) return false;
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
