package com.ing.ide.main.apigeneration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LLM output that uses SCENARIO/TESTCASE delimiter blocks into
 * {@link ApiTestScenario} objects ready for {@link ApiTestStepImporter}.
 *
 * Expected format:
 * <pre>
 * =====SCENARIO_START: My_Scenario=====
 * =====TESTCASE_START: Happy_Path=====
 * setEndPoint|https://api.example.com/login||
 * addHeader|Content-Type=application/json||
 * postRestRequest|{"user":"a","pass":"b"}||
 * assertResponseCode|200||
 * closeConnection|||
 * =====TESTCASE_END=====
 * =====SCENARIO_END=====
 * </pre>
 *
 * Each step row is pipe-delimited: action|input|condition|description
 * (description is optional, condition may be empty).
 */
public final class ApiOutputParser {

    private static final Pattern SCENARIO_START = Pattern.compile(
            "={5}SCENARIO_START:\\s*(.+?)={5}", Pattern.CASE_INSENSITIVE);
    private static final Pattern TESTCASE_START = Pattern.compile(
            "={5}TESTCASE_START:\\s*(.+?)={5}", Pattern.CASE_INSENSITIVE);

    private ApiOutputParser() {}

    public static List<ApiTestScenario> parse(String llmOutput) {
        List<ApiTestScenario> scenarios = new ArrayList<>();
        if (llmOutput == null || llmOutput.isBlank()) return scenarios;

        String[] lines = llmOutput.split("\n");

        String              currentScenario = null;
        boolean             currentReusable = false;
        String              currentTestCase = null;
        List<ApiTestCase>   currentTcs      = null;
        List<ApiTestRow>    currentSteps    = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            // ── SCENARIO_START ───────────────────────────────────────────────
            Matcher sm = SCENARIO_START.matcher(line);
            if (sm.find()) {
                flushTestCase(currentTcs, currentTestCase, currentSteps);
                currentTestCase = null; currentSteps = null;
                if (currentScenario != null && currentTcs != null) {
                    scenarios.add(new ApiTestScenario(currentScenario, currentTcs, currentReusable));
                }
                String rawName = sm.group(1).trim();
                // LLM marks reusable scenarios with [REUSABLE] suffix
                currentReusable = rawName.toUpperCase().contains("[REUSABLE]");
                String cleanName = rawName.replaceAll("(?i)\\s*\\[REUSABLE]\\s*", "").trim();
                currentScenario = sanitize(cleanName);
                currentTcs      = new ArrayList<>();
                continue;
            }

            // ── SCENARIO_END ─────────────────────────────────────────────────
            if (line.startsWith("=====SCENARIO_END")) {
                flushTestCase(currentTcs, currentTestCase, currentSteps);
                currentTestCase = null; currentSteps = null;
                if (currentScenario != null && currentTcs != null) {
                    scenarios.add(new ApiTestScenario(currentScenario, currentTcs, currentReusable));
                }
                currentScenario = null; currentReusable = false; currentTcs = null;
                continue;
            }

            // ── TESTCASE_START ───────────────────────────────────────────────
            Matcher tm = TESTCASE_START.matcher(line);
            if (tm.find()) {
                flushTestCase(currentTcs, currentTestCase, currentSteps);
                currentTestCase = sanitize(tm.group(1).trim());
                currentSteps    = new ArrayList<>();
                continue;
            }

            // ── TESTCASE_END ─────────────────────────────────────────────────
            if (line.startsWith("=====TESTCASE_END")) {
                flushTestCase(currentTcs, currentTestCase, currentSteps);
                currentTestCase = null; currentSteps = null;
                continue;
            }

            // ── Step row ─────────────────────────────────────────────────────
            if (currentSteps != null && line.contains("|")) {
                String[] p = line.split("\\|", -1);
                String action  = p.length > 0 ? p[0].trim() : "";
                String input   = p.length > 1 ? p[1].trim() : "";
                String cond    = p.length > 2 ? p[2].trim() : "";
                String desc    = p.length > 3 ? p[3].trim() : "";
                // Skip the literal column-header row the LLM sometimes emits
                if ("action".equalsIgnoreCase(action) && "input".equalsIgnoreCase(input)) continue;
                if (!action.isEmpty()) {
                    currentSteps.add(new ApiTestRow(action, input, cond, desc));
                }
            }
        }

        // Flush any unclosed blocks
        flushTestCase(currentTcs, currentTestCase, currentSteps);
        if (currentScenario != null && currentTcs != null && !currentTcs.isEmpty()) {
            scenarios.add(new ApiTestScenario(currentScenario, currentTcs, currentReusable));
        }

        return scenarios;
    }

    private static void flushTestCase(List<ApiTestCase> tcs, String name, List<ApiTestRow> steps) {
        if (tcs != null && name != null && steps != null && !steps.isEmpty()) {
            tcs.add(new ApiTestCase(name, steps));
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "API_Test";
        return name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_").replaceAll("_+", "_");
    }
}
