package com.ing.ide.main.apigeneration;

import com.ing.datalib.component.Project;
import com.ing.datalib.component.Scenario;
import com.ing.datalib.component.TestCase;
import com.ing.datalib.component.TestStep;
import com.ing.ide.main.mainui.AppMainFrame;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts {@link ApiTestScenario} objects into INGenious {@link TestCase}/{@link TestStep}
 * CSV files.
 *
 * - Regular scenarios → TestPlan
 * - Reusable scenarios (marked with [REUSABLE] by the LLM) → ReusableComponents
 *
 * Step routing:
 * - action == "Execute" → ObjectName=Execute, Action=input (e.g. "Auth:Login")
 * - everything else    → ObjectName=Webservice, Action=action, Input=input
 */
public class ApiTestStepImporter {

    private static final Logger LOG              = Logger.getLogger(ApiTestStepImporter.class.getName());
    private static final String WEBSERVICE_OBJECT = "Webservice";
    private static final String EXECUTE_OBJECT    = "Execute";

    private final AppMainFrame mainFrame;

    public ApiTestStepImporter(AppMainFrame mainFrame) {
        this.mainFrame = mainFrame;
    }

    /** Callback so the caller can stream progress to a log area. */
    public interface ImportListener {
        void onLog(String message);
    }

    /**
     * Writes all scenarios and their test cases to the project.
     *
     * @return total number of test cases successfully written
     */
    public int importAll(List<ApiTestScenario> scenarios, ImportListener listener) {
        Project project = mainFrame.getProject();
        if (project == null) {
            log(listener, "ERROR: No project is open.");
            return 0;
        }

        int count = 0;
        for (ApiTestScenario scenarioDef : scenarios) {
            String scenarioName = sanitize(scenarioDef.scenarioName);

            Scenario scenario;
            if (scenarioDef.reusable) {
                scenario = project.getReusableScenarioByName(scenarioName);
                if (scenario == null) scenario = project.addReusableScenario(scenarioName);
                log(listener, "Reusable scenario: " + scenarioName);
            } else {
                scenario = project.getTestPlanScenarioByName(scenarioName);
                if (scenario == null) scenario = project.addScenario(scenarioName);
            }

            if (scenario == null) {
                log(listener, "ERROR: Could not create scenario: " + scenarioName);
                continue;
            }

            for (ApiTestCase tcDef : scenarioDef.testCases) {
                String tcName = uniqueName(scenario, sanitize(tcDef.name));
                TestCase tc = scenario.addTestCase(tcName);
                if (tc == null) {
                    log(listener, "SKIP: " + scenarioName + "/" + tcName + " (already exists)");
                    continue;
                }

                for (ApiTestRow row : tcDef.steps) {
                    TestStep step = tc.addNewStep();
                    step.setDescription(row.description);
                    step.setCondition(row.condition);

                    if (EXECUTE_OBJECT.equalsIgnoreCase(row.action)) {
                        // Execute|Auth:Login||  →  ObjectName=Execute, Action=Auth:Login
                        step.setObject(EXECUTE_OBJECT);
                        step.setAction(row.input);
                        step.setInput("");
                    } else {
                        step.setObject(WEBSERVICE_OBJECT);
                        step.setAction(row.action);
                        step.setInput(row.input);
                    }
                }

                tc.setSaved(false);
                tc.save();
                count++;
                log(listener, "  Imported: " + scenarioName + "/" + tcName
                        + "  (" + tcDef.steps.size() + " steps)"
                        + (scenarioDef.reusable ? " [reusable]" : ""));
            }
        }
        return count;
    }

    // If the base name is already taken in this scenario, append _2, _3, etc.
    private String uniqueName(Scenario scenario, String base) {
        if (scenario.getTestCaseByName(base) == null) return base;
        int i = 2;
        while (scenario.getTestCaseByName(base + "_" + i) != null) i++;
        return base + "_" + i;
    }

    private void log(ImportListener listener, String msg) {
        LOG.log(Level.INFO, msg);
        if (listener != null) listener.onLog(msg);
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) return "API_Tests";
        return name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
