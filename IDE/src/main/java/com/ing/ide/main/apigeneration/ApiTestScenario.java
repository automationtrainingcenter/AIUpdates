package com.ing.ide.main.apigeneration;

import java.util.List;

/**
 * One generated scenario — a folder name plus the test cases it contains.
 *
 * When {@code reusable} is true the scenario is saved under ReusableComponents
 * instead of TestPlan.  The LLM marks reusable scenarios with the
 * {@code [REUSABLE]} suffix in the SCENARIO_START delimiter line.
 */
public class ApiTestScenario {

    public final String           scenarioName;
    public final List<ApiTestCase> testCases;
    public final boolean          reusable;

    public ApiTestScenario(String scenarioName, List<ApiTestCase> testCases) {
        this(scenarioName, testCases, false);
    }

    public ApiTestScenario(String scenarioName, List<ApiTestCase> testCases, boolean reusable) {
        this.scenarioName = scenarioName;
        this.testCases    = testCases;
        this.reusable     = reusable;
    }
}
