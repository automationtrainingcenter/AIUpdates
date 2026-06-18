package com.ing.ide.main.mainui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ing.datalib.api.APICollection;
import com.ing.datalib.settings.TestMgmtModule;
import com.ing.ide.main.apigeneration.ApiHttpToolAgent;
import com.ing.ide.main.apigeneration.ApiLlmClient;
import com.ing.ide.main.apigeneration.ApiOutputParser;
import com.ing.ide.main.apigeneration.ApiTestCaseGenerator;
import com.ing.ide.main.apigeneration.ApiTestScenario;
import com.ing.ide.main.apigeneration.ApiTestStepImporter;
import com.ing.ide.main.apigeneration.OpenApiSpecExtractor;
import com.ing.ide.main.playwrightrecording.AITestGenerationAgent;
import com.ing.ide.main.playwrightrecording.PlaywrightRecordingParser;
import com.ing.ide.main.settings.AISettings;
import com.ing.ide.util.Notification;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class AITestGeneratorDialog extends JFrame {

    private static final Logger LOG = Logger.getLogger(AITestGeneratorDialog.class.getName());

    // ── Web generation sub-modes ──────────────────────────────────────────────
    private static final String MODE_PROMPT = "FROM_PROMPT";
    private static final String MODE_AZURE  = "FROM_AZURE";

    // ── API generation sub-modes ─────────────────────────────────────────────
    private static final String API_MODE_COLLECTION = "API_COLLECTION";
    private static final String API_MODE_SPEC       = "API_SPEC";
    private static final String API_MODE_PROMPT     = "API_PROMPT";

    private final AppMainFrame sMainFrame;

    // ── Top-level tab ────────────────────────────────────────────────────────
    private JTabbedPane testTypeTabs;

    // ── Web tab ──────────────────────────────────────────────────────────────
    private JRadioButton promptRadio;
    private JRadioButton azureRadio;
    private JTextArea    promptArea;
    private JPanel       webInputCards;

    // Azure inline form
    private JTextField azureUrlField;
    private JTextField azurePatField;
    private JTextField azureProjectField;
    private JTextField azurePlanIdField;

    // ── API tab ───────────────────────────────────────────────────────────────
    private JRadioButton apiCollectionRadio;
    private JRadioButton apiSpecRadio;
    private JRadioButton apiPromptRadio;
    private JPanel       apiInputCards;

    // API Mode 1 – From Workbench Collection
    private JComboBox<String> collectionCombo;
    private JTextField        apiScenarioNameField;
    private final List<APICollection> loadedCollections = new ArrayList<>();

    // API Mode 2 – From OpenAPI Spec
    private JTextField specUrlField;
    private JTextField apiBaseUrlOverrideField;
    private JRadioButton filterByTagRadio;
    private JRadioButton filterByPathRadio;
    private JTextField   filterValueField;
    private JTextArea    specHintArea;

    // API Mode 3 – From AI Prompt + Live API
    private JTextField apiBaseUrlField;
    private JTextArea  apiNlPromptArea;

    // ── Shared bottom section ────────────────────────────────────────────────
    private JButton   generateBtn;
    private JButton   cancelBtn;
    private JTextArea logArea;

    // Import panel (shown after LLM generation completes)
    private JPanel    importPanel;
    private JLabel    scenarioCountLabel;
    private JTextArea scenarioNamesArea;
    private JButton   importBtn;

    // ── Agent state — web (Playwright MCP) ────────────────────────────────────
    private AITestGenerationAgent currentAgent;
    private Disposable subscription;
    private final StringBuilder agentOutput = new StringBuilder();
    private final List<AITestGenerationAgent.ScenarioGroup> scenarioGroups = new ArrayList<>();

    // ── Agent state — API Mode 3 (HTTP tool agent) ────────────────────────────
    private ApiHttpToolAgent currentApiAgent;
    private Disposable       apiSubscription;
    private final StringBuilder apiAgentOutput = new StringBuilder();

    // ── Jackson for loading API collections ───────────────────────────────────
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────────

    public AITestGeneratorDialog(AppMainFrame sMainFrame) {
        this.sMainFrame = sMainFrame;
        setTitle("AI Test Generator");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setResizable(true);
        initUI();
        loadApiCollections();
        pack();
        setMinimumSize(new Dimension(680, 680));
    }

    // =========================================================================
    // UI construction
    // =========================================================================

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(buildMainPanel(),   BorderLayout.CENTER);
        root.add(buildButtonPanel(), BorderLayout.SOUTH);
        getContentPane().add(root);
    }

    /** Stacks tabs → log → import panel vertically. */
    private JPanel buildMainPanel() {
        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));

        testTypeTabs = new JTabbedPane();
        testTypeTabs.addTab("Web Tests", buildWebTab());
        testTypeTabs.addTab("API Tests", buildApiTab());
        testTypeTabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
        main.add(testTypeTabs);

        main.add(buildLogPanel());

        importPanel = buildImportPanel();
        importPanel.setVisible(false);
        main.add(importPanel);

        return main;
    }

    // ── Web tab ───────────────────────────────────────────────────────────────

    private JPanel buildWebTab() {
        JPanel tab = new JPanel(new BorderLayout(4, 4));
        tab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        tab.add(buildWebModePanel(), BorderLayout.NORTH);
        tab.add(buildWebInputCards(), BorderLayout.CENTER);
        return tab;
    }

    private JPanel buildWebModePanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        p.setBorder(BorderFactory.createTitledBorder("Generation Mode"));
        promptRadio = new JRadioButton("From Prompt / Requirement", true);
        azureRadio  = new JRadioButton("From Azure Test Plan");
        ButtonGroup bg = new ButtonGroup();
        bg.add(promptRadio);
        bg.add(azureRadio);
        promptRadio.addActionListener(e -> switchWebMode(MODE_PROMPT));
        azureRadio.addActionListener(e ->  switchWebMode(MODE_AZURE));
        p.add(promptRadio);
        p.add(azureRadio);
        return p;
    }

    private JPanel buildWebInputCards() {
        webInputCards = new JPanel(new CardLayout());
        webInputCards.add(buildPromptCard(), MODE_PROMPT);
        webInputCards.add(buildAzureCard(),  MODE_AZURE);
        return webInputCards;
    }

    private JPanel buildPromptCard() {
        JPanel card = new JPanel(new BorderLayout(4, 4));
        card.setBorder(BorderFactory.createTitledBorder("Requirement"));
        promptArea = new JTextArea(4, 50);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setFont(UIManager.getFont("TextArea.font"));
        promptArea.setText("Example: Test the login page with valid and invalid credentials.");
        card.add(new JScrollPane(promptArea), BorderLayout.CENTER);
        return card;
    }

    private JPanel buildAzureCard() {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createTitledBorder("Azure Test Plan"));

        GridBagConstraints lc = new GridBagConstraints();
        lc.insets = new Insets(4, 8, 4, 4);
        lc.anchor = GridBagConstraints.WEST;
        lc.gridx  = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.insets  = new Insets(4, 4, 4, 8);
        fc.anchor  = GridBagConstraints.WEST;
        fc.fill    = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.gridx   = 1;

        azureUrlField     = new JTextField(36);
        azurePatField     = new JPasswordField(36);
        azureProjectField = new JTextField(36);
        azurePlanIdField  = new JTextField(36);

        lc.gridy = fc.gridy = 0; card.add(new JLabel("Organization URL:"),      lc); card.add(azureUrlField,     fc);
        lc.gridy = fc.gridy = 1; card.add(new JLabel("Personal Access Token:"), lc); card.add(azurePatField,     fc);
        lc.gridy = fc.gridy = 2; card.add(new JLabel("Project:"),               lc); card.add(azureProjectField, fc);
        lc.gridy = fc.gridy = 3; card.add(new JLabel("Test Plan ID:"),          lc); card.add(azurePlanIdField,  fc);

        try {
            if (sMainFrame != null && sMainFrame.getProject() != null) {
                String loc = sMainFrame.getProject().getLocation();
                TestMgmtModule tm = new TestMgmtModule(loc + File.separator + "Settings");
                java.util.Map<String, String> m = tm.asMap();
                azureUrlField.setText(    m.getOrDefault("AzureDevOps URL", ""));
                azureProjectField.setText(m.getOrDefault("AzureDevOps Project", ""));
                azurePlanIdField.setText( m.getOrDefault("AzureDevOps TestPlanId", ""));
                azurePatField.setText(    m.getOrDefault("PersonalAccessToken", ""));
            }
        } catch (Exception ignored) {}

        return card;
    }

    // ── API tab ───────────────────────────────────────────────────────────────

    private JPanel buildApiTab() {
        JPanel tab = new JPanel(new BorderLayout(4, 4));
        tab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        tab.add(buildApiModePanel(),  BorderLayout.NORTH);
        tab.add(buildApiInputCards(), BorderLayout.CENTER);
        return tab;
    }

    private JPanel buildApiModePanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        p.setBorder(BorderFactory.createTitledBorder("API Generation Mode"));

        apiCollectionRadio = new JRadioButton("From Workbench Collection", true);
        apiSpecRadio       = new JRadioButton("From OpenAPI Spec");
        apiPromptRadio     = new JRadioButton("From AI Prompt + Live API");

        ButtonGroup bg = new ButtonGroup();
        bg.add(apiCollectionRadio);
        bg.add(apiSpecRadio);
        bg.add(apiPromptRadio);

        apiCollectionRadio.addActionListener(e -> switchApiMode(API_MODE_COLLECTION));
        apiSpecRadio.addActionListener(e ->       switchApiMode(API_MODE_SPEC));
        apiPromptRadio.addActionListener(e ->     switchApiMode(API_MODE_PROMPT));

        p.add(apiCollectionRadio);
        p.add(apiSpecRadio);
        p.add(apiPromptRadio);
        return p;
    }

    private JPanel buildApiInputCards() {
        apiInputCards = new JPanel(new CardLayout());
        apiInputCards.add(buildCollectionCard(), API_MODE_COLLECTION);
        apiInputCards.add(buildSpecCard(),       API_MODE_SPEC);
        apiInputCards.add(buildApiPromptCard(),  API_MODE_PROMPT);
        return apiInputCards;
    }

    /** Mode 1 – pick a saved workbench collection and a scenario name. */
    private JPanel buildCollectionCard() {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createTitledBorder("Workbench Collection"));

        GridBagConstraints lc = new GridBagConstraints();
        lc.insets = new Insets(6, 8, 6, 4);
        lc.anchor = GridBagConstraints.WEST;
        lc.gridx  = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.insets  = new Insets(6, 4, 6, 8);
        fc.fill    = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.gridx   = 1;

        collectionCombo      = new JComboBox<>();
        apiScenarioNameField = new JTextField(30);

        // Auto-update scenario name when collection changes
        collectionCombo.addActionListener(e -> {
            int idx = collectionCombo.getSelectedIndex();
            if (idx >= 0 && idx < loadedCollections.size()) {
                apiScenarioNameField.setText(loadedCollections.get(idx).getName());
            }
        });

        lc.gridy = fc.gridy = 0;
        card.add(new JLabel("Collection:"), lc);
        card.add(collectionCombo, fc);

        lc.gridy = fc.gridy = 1;
        card.add(new JLabel("Scenario name:"), lc);
        card.add(apiScenarioNameField, fc);

        lc.gridy = 2; fc.gridy = 2;
        fc.gridwidth = 2; lc.gridwidth = 2;
        card.add(new JLabel(
                "<html><i>Each request in the collection becomes one test case.</i></html>"),
                lc);

        return card;
    }

    /** Mode 2 – OpenAPI spec URL + controller/endpoint filter + hint. */
    private JPanel buildSpecCard() {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createTitledBorder("OpenAPI / Swagger Spec"));

        GridBagConstraints lc = new GridBagConstraints();
        lc.insets = new Insets(4, 8, 4, 4);
        lc.anchor = GridBagConstraints.WEST;
        lc.gridx  = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.insets  = new Insets(4, 4, 4, 8);
        fc.fill    = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.gridx   = 1;

        specUrlField           = new JTextField(36);
        apiBaseUrlOverrideField = new JTextField(36);
        apiBaseUrlOverrideField.setToolTipText(
                "Optional — overrides the server URL from the spec (e.g. https://staging.myapp.com)");

        filterByTagRadio  = new JRadioButton("Controller / Tag", true);
        filterByPathRadio = new JRadioButton("Endpoint Path");
        ButtonGroup fg = new ButtonGroup();
        fg.add(filterByTagRadio);
        fg.add(filterByPathRadio);

        filterValueField = new JTextField(36);

        specHintArea = new JTextArea(2, 36);
        specHintArea.setLineWrap(true);
        specHintArea.setWrapStyleWord(true);
        specHintArea.setFont(UIManager.getFont("TextArea.font"));

        JPanel filterRadioRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        filterRadioRow.add(new JLabel("Filter by:"));
        filterRadioRow.add(filterByTagRadio);
        filterRadioRow.add(filterByPathRadio);

        lc.gridy = fc.gridy = 0;
        card.add(new JLabel("Spec URL (JSON):"), lc);
        card.add(specUrlField, fc);

        lc.gridy = fc.gridy = 1;
        card.add(new JLabel("Base URL (optional):"), lc);
        card.add(apiBaseUrlOverrideField, fc);

        lc.gridy = 2; fc.gridy = 2; fc.gridwidth = 2;
        card.add(filterRadioRow, fc);
        fc.gridwidth = 1;

        lc.gridy = fc.gridy = 3;
        card.add(new JLabel("Value:"), lc);
        card.add(filterValueField, fc);

        lc.gridy = fc.gridy = 4;
        card.add(new JLabel("What to test:"), lc);
        card.add(new JScrollPane(specHintArea), fc);

        return card;
    }

    /** Mode 3 – base URL + natural-language description. */
    private JPanel buildApiPromptCard() {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createTitledBorder("AI Prompt + Live API"));

        GridBagConstraints lc = new GridBagConstraints();
        lc.insets = new Insets(4, 8, 4, 4);
        lc.anchor = GridBagConstraints.WEST;
        lc.gridx  = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.insets  = new Insets(4, 4, 4, 8);
        fc.fill    = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.gridx   = 1;

        apiBaseUrlField = new JTextField(36);

        apiNlPromptArea = new JTextArea(3, 36);
        apiNlPromptArea.setLineWrap(true);
        apiNlPromptArea.setWrapStyleWord(true);
        apiNlPromptArea.setFont(UIManager.getFont("TextArea.font"));
        apiNlPromptArea.setText(
                "Example: Test login with valid and invalid credentials, " +
                "then fetch user profile using the returned token.");

        lc.gridy = fc.gridy = 0;
        card.add(new JLabel("Base URL:"), lc);
        card.add(apiBaseUrlField, fc);

        lc.gridy = fc.gridy = 1;
        card.add(new JLabel("What to test:"), lc);
        card.add(new JScrollPane(apiNlPromptArea), fc);

        lc.gridy = 2; fc.gridy = 2; fc.gridwidth = 2;
        card.add(new JLabel(
                "<html><i>The AI agent will call the live API to observe real responses.</i></html>"),
                fc);

        return card;
    }

    // ── Shared sections ───────────────────────────────────────────────────────

    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("Agent Progress"));
        logArea = new JTextArea(10, 50);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBackground(UIManager.getColor("TextArea.background"));
        p.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildImportPanel() {
        JPanel p = new JPanel(new BorderLayout(8, 4));
        p.setBorder(BorderFactory.createTitledBorder("Import Generated Scenarios"));

        scenarioCountLabel = new JLabel("0 scenario(s) ready — edit names below if needed:");
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        topRow.add(scenarioCountLabel);
        p.add(topRow, BorderLayout.NORTH);

        scenarioNamesArea = new JTextArea(4, 50);
        scenarioNamesArea.setFont(UIManager.getFont("TextArea.font"));
        scenarioNamesArea.setToolTipText(
                "One scenario folder name per line. Each scenario contains multiple test case CSV files.");
        p.add(new JScrollPane(scenarioNamesArea), BorderLayout.CENTER);

        importBtn = new JButton("Import All into INGenious");
        importBtn.addActionListener(e -> importWebSteps());
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        btnRow.add(importBtn);
        p.add(btnRow, BorderLayout.SOUTH);

        return p;
    }

    private JPanel buildButtonPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        generateBtn = new JButton("Generate");
        cancelBtn   = new JButton("Cancel");
        generateBtn.addActionListener(e -> startGeneration());
        cancelBtn.addActionListener(e -> handleCancel());
        p.add(generateBtn);
        p.add(cancelBtn);
        return p;
    }

    // =========================================================================
    // Mode switching
    // =========================================================================

    private void switchWebMode(String mode) {
        ((CardLayout) webInputCards.getLayout()).show(webInputCards, mode);
    }

    private void switchApiMode(String mode) {
        ((CardLayout) apiInputCards.getLayout()).show(apiInputCards, mode);
    }

    // =========================================================================
    // Collection loading
    // =========================================================================

    private void loadApiCollections() {
        loadedCollections.clear();
        collectionCombo.removeAllItems();

        String projectLocation = getProjectLocation();
        if (projectLocation == null) {
            collectionCombo.addItem("(no project open)");
            return;
        }

        Path collectionsDir = Path.of(projectLocation, "api", "collections");
        if (!Files.exists(collectionsDir)) {
            collectionCombo.addItem("(no collections saved)");
            return;
        }

        try {
            Files.list(collectionsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            APICollection col = objectMapper.readValue(p.toFile(), APICollection.class);
                            loadedCollections.add(col);
                            collectionCombo.addItem(col.getName());
                        } catch (IOException ex) {
                            LOG.log(Level.WARNING, "Could not load collection: " + p, ex);
                        }
                    });
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not list collections directory", ex);
        }

        if (loadedCollections.isEmpty()) {
            collectionCombo.addItem("(no collections saved)");
        } else {
            // seed the scenario name field
            apiScenarioNameField.setText(loadedCollections.get(0).getName());
        }
    }

    // =========================================================================
    // Generation dispatch
    // =========================================================================

    private void startGeneration() {
        if (testTypeTabs.getSelectedIndex() == 1) {
            startApiGeneration();
        } else {
            startWebGeneration();
        }
    }

    // ── API generation ────────────────────────────────────────────────────────

    private void startApiGeneration() {
        if (apiCollectionRadio.isSelected()) {
            generateFromCollection();
        } else if (apiSpecRadio.isSelected()) {
            generateFromSpec();
        } else {
            generateFromAiPrompt();
        }
    }

    // ─── Mode 1: From Workbench Collection ───────────────────────────────────

    private void generateFromCollection() {
        String projectLocation = getProjectLocation();
        if (projectLocation == null) {
            Notification.show("Please open a project before generating tests.");
            return;
        }
        if (loadedCollections.isEmpty()) {
            Notification.show("No collections found. Save requests in the API Workbench first.");
            return;
        }
        int idx = collectionCombo.getSelectedIndex();
        if (idx < 0 || idx >= loadedCollections.size()) {
            Notification.show("Please select a collection.");
            return;
        }

        APICollection collection = loadedCollections.get(idx);
        String scenarioName = apiScenarioNameField.getText().trim();
        if (scenarioName.isEmpty()) scenarioName = collection.getName();

        logArea.setText("");
        importPanel.setVisible(false);
        generateBtn.setEnabled(false);

        final String finalScenarioName = scenarioName;

        new Thread(() -> {
            SwingUtilities.invokeLater(() ->
                    appendLog("Generating from collection: " + collection.getName() + " ..."));

            List<ApiTestScenario> scenarios = ApiTestCaseGenerator.fromCollection(collection);

            List<ApiTestScenario> renamed = scenarios.stream()
                    .map(s -> new ApiTestScenario(finalScenarioName, s.testCases))
                    .toList();

            ApiTestStepImporter importer = new ApiTestStepImporter(sMainFrame);
            int count = importer.importAll(renamed,
                    msg -> SwingUtilities.invokeLater(() -> appendLog(msg)));

            SwingUtilities.invokeLater(() -> {
                appendLog("");
                appendLog("Done — " + count + " test case(s) imported into scenario '"
                        + finalScenarioName + "'.");
                generateBtn.setEnabled(true);
                if (count > 0) reloadProject(projectLocation);
            });
        }, "API-Collection-Generator").start();
    }

    // ─── Mode 2: From OpenAPI Spec (one-shot LLM, no tools) ─────────────────

    private void generateFromSpec() {
        String projectLocation = getProjectLocation();
        if (projectLocation == null) {
            Notification.show("Please open a project before generating tests.");
            return;
        }
        String specUrl = specUrlField.getText().trim();
        if (specUrl.isEmpty()) {
            Notification.show("Please enter the OpenAPI spec URL.");
            return;
        }

        Properties aiProps = loadAiSettings(projectLocation);
        String apiKey = aiProps.getProperty(AISettings.KEY_OPENROUTER_KEY, "").trim();
        String model  = aiProps.getProperty(AISettings.KEY_MODEL, AISettings.DEFAULT_MODEL).trim();
        if (apiKey.isEmpty()) {
            Notification.show("Please configure your OpenRouter API key in Tools > AI Settings.");
            return;
        }

        boolean filterByTag   = filterByTagRadio.isSelected();
        String  filterValue   = filterValueField.getText().trim();
        String  hint          = specHintArea.getText().trim();
        String  baseUrlOverride = apiBaseUrlOverrideField.getText().trim();

        logArea.setText("");
        importPanel.setVisible(false);
        generateBtn.setEnabled(false);
        cancelBtn.setText("Stop");

        final String finalApiKey       = apiKey;
        final String finalModel        = model;
        final String finalBaseOverride = baseUrlOverride;

        new Thread(() -> {
            ApiLlmClient client = new ApiLlmClient(finalApiKey, finalModel);
            try {
                appendLog("Fetching spec from: " + specUrl);
                OpenApiSpecExtractor extractor = new OpenApiSpecExtractor();
                String specSlice = extractor.loadAndFilter(specUrl, filterByTag, filterValue);

                appendLog("Spec fetched (" + specSlice.length() + " chars) — filtering by "
                        + (filterByTag ? "tag" : "path")
                        + (filterValue.isEmpty() ? " (no filter)" : ": " + filterValue));

                // Warn if the slice is still very large — most models have ~128k context
                if (specSlice.length() > 80_000) {
                    appendLog("WARNING: Spec slice is large (" + specSlice.length()
                            + " chars). Consider narrowing the filter value.");
                }

                if (!finalBaseOverride.isEmpty()) {
                    appendLog("Using base URL override: " + finalBaseOverride);
                }

                String systemPrompt = buildSpecSystemPrompt(finalBaseOverride);
                String userMessage  = buildSpecUserMessage(specSlice, hint);
                appendLog("Sending to LLM (" + finalModel + ") — "
                        + (systemPrompt.length() + userMessage.length()) + " chars total...");

                String llmOutput = client.call(systemPrompt, userMessage);

                appendLog("LLM response received (" + llmOutput.length() + " chars) — parsing...");

                List<ApiTestScenario> scenarios = ApiOutputParser.parse(llmOutput);
                if (scenarios.isEmpty()) {
                    appendLog("WARNING: No test scenarios found in LLM output.");
                    appendLog("── Raw LLM output ──────────────────────────────");
                    appendLog(llmOutput.isEmpty() ? "(empty)" : llmOutput);
                    appendLog("────────────────────────────────────────────────");
                    SwingUtilities.invokeLater(this::resetButtons);
                    return;
                }

                appendLog("Parsed " + scenarios.size() + " scenario(s). Importing...");
                ApiTestStepImporter importer = new ApiTestStepImporter(sMainFrame);
                int count = importer.importAll(scenarios,
                        msg -> SwingUtilities.invokeLater(() -> appendLog(msg)));

                SwingUtilities.invokeLater(() -> {
                    appendLog("");
                    appendLog("Done — " + count + " test case(s) imported from OpenAPI spec.");
                    resetButtons();
                    if (count > 0) reloadProject(projectLocation);
                });

            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "Mode 2 spec generation error", ex);
                // Also show the raw LLM response if available (helps diagnose model quirks)
                String raw = client.getLastRawResponse();
                SwingUtilities.invokeLater(() -> {
                    appendLog("ERROR: " + ex.getMessage());
                    if (!raw.isEmpty()) {
                        appendLog("── Raw OpenRouter response (first 600 chars) ────");
                        appendLog(raw.length() > 600 ? raw.substring(0, 600) + "..." : raw);
                        appendLog("────────────────────────────────────────────────");
                    }
                    resetButtons();
                });
            }
        }, "API-Spec-Generator").start();
    }

    private String buildSpecSystemPrompt(String baseUrlOverride) {
        String urlRule = baseUrlOverride.isEmpty()
                ? "- Build the full URL by combining the server URL from the spec's 'servers' or 'host'+'basePath' fields with the endpoint path."
                : "- Use this exact base URL for ALL endpoints (ignore any server URL in the spec): " + baseUrlOverride;

        return """
                You are an API test generation expert for INGenious Playwright Studio.
                Given a filtered OpenAPI spec, generate test cases for each endpoint.

                ═══ OUTPUT FORMAT ═══
                Use EXACTLY these delimiters. No prose, markdown, or text outside delimiter blocks.

                =====SCENARIO_START: <scenario_name>=====
                =====TESTCASE_START: <test_case_name>=====
                action|input|condition|description
                action|input|condition|description
                =====TESTCASE_END=====
                =====SCENARIO_END=====

                ═══ AUTHENTICATION — REUSABLE PATTERN ═══
                If the API requires authentication (Bearer token, CSRF, API key, etc.):

                STEP 1 — Output an Auth reusable scenario FIRST (before any other scenario):
                =====SCENARIO_START: Auth [REUSABLE]=====
                =====TESTCASE_START: Login=====
                setEndPoint|<full_login_url>||Set login endpoint
                addHeader|Content-Type=application/json||Set content type
                postRestRequest|{"identifier":"test@example.com","password":"Test@1234"}||Send login request
                assertResponseCode|200||Assert login successful
                storeJSONelement|$.data.accessToken|%authToken%|Store bearer token
                closeConnection|||End login request
                =====TESTCASE_END=====
                =====SCENARIO_END=====

                Note for storeJSONelement: input=JSONPath, condition=%variableName%

                If a separate CSRF token endpoint exists, add another test case inside Auth [REUSABLE]:
                =====TESTCASE_START: FetchCsrf=====
                setEndPoint|<full_csrf_url>||Set CSRF endpoint
                addHeader|Authorization=Bearer %authToken%||Add auth header
                getRestRequest|||Fetch CSRF token
                assertResponseCode|200||Assert CSRF request successful
                storeJSONelement|$.data.csrfToken|%csrfToken%|Store CSRF token
                closeConnection|||End CSRF request
                =====TESTCASE_END=====

                STEP 2 — Start every protected test case with Execute steps:
                =====TESTCASE_START: <name>=====
                Execute|Auth:Login||Execute reusable login to get auth token
                Execute|Auth:FetchCsrf||Fetch CSRF token (include only if CSRF required)
                setEndPoint|<full_url>||Set endpoint
                addHeader|Authorization=Bearer %authToken%||Add bearer token
                addHeader|X-CSRF-Token=%csrfToken%||Add CSRF token (include only if CSRF required)
                ...rest of steps...
                closeConnection|||End request
                =====TESTCASE_END=====

                For Execute steps: action=Execute, input=ScenarioName:TestCaseName (e.g. Auth:Login)

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
                assertJSONelementEquals|<expected>|<$.jsonpath>|Assert JSON value equals expected
                assertJSONelementNotEquals|<expected>|<$.jsonpath>|Assert JSON value not equals
                assertJSONelementContains|<expected>|<$.jsonpath>|Assert JSON value contains text
                assertResponsebodycontains|<expected>||Assert response body contains text
                assertHeaderValueEquals|<expected>|<header-name>|Assert response header value
                storeJSONelement|<$.jsonpath>|<%varName%>|Store JSON response value to variable
                Execute|<ScenarioName:TestCaseName>||Call a reusable test case
                closeConnection|||End request (always last in a test case)

                ═══ RULES ═══
                - Every test case MUST start with setEndPoint (or Execute) and end with closeConnection.
                - Group endpoints by resource/controller into one scenario each.
                - Generate both happy-path and error-path test cases (400, 401, 403, 404, 409, etc.).
                - Use realistic but generic test data — no real credentials, use placeholders.
                - For error cases, omit Execute steps (to test unauthenticated / no-CSRF scenarios).
                """
                + urlRule + "\n";
    }

    private String buildSpecUserMessage(String specSlice, String hint) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here is the filtered OpenAPI spec:\n\n");
        sb.append(specSlice);
        if (!hint.isEmpty()) {
            sb.append("\n\nAdditional guidance — what to test:\n").append(hint);
        }
        sb.append("\n\nGenerate INGenious API test cases for all endpoints in the spec above.");
        return sb.toString();
    }

    // ─── Mode 3: From AI Prompt + Live API (ADK tool agent) ─────────────────

    private void generateFromAiPrompt() {
        String projectLocation = getProjectLocation();
        if (projectLocation == null) {
            Notification.show("Please open a project before generating tests.");
            return;
        }
        String baseUrl  = apiBaseUrlField.getText().trim();
        String nlPrompt = apiNlPromptArea.getText().trim();
        if (baseUrl.isEmpty()) {
            Notification.show("Please enter the API Base URL.");
            return;
        }
        if (nlPrompt.isEmpty()) {
            Notification.show("Please describe what you want to test.");
            return;
        }

        Properties aiProps = loadAiSettings(projectLocation);
        String apiKey = aiProps.getProperty(AISettings.KEY_OPENROUTER_KEY, "").trim();
        String model  = aiProps.getProperty(AISettings.KEY_MODEL, AISettings.DEFAULT_MODEL).trim();
        if (apiKey.isEmpty()) {
            Notification.show("Please configure your OpenRouter API key in Tools > AI Settings.");
            return;
        }

        apiAgentOutput.setLength(0);
        logArea.setText("");
        importPanel.setVisible(false);
        generateBtn.setEnabled(false);
        cancelBtn.setText("Stop");

        RxJavaPlugins.setErrorHandler(ex -> SwingUtilities.invokeLater(() -> {
            onApiError(ex);
            RxJavaPlugins.setErrorHandler(null);
        }));

        appendLog("Starting API agent (model: " + model + ")...");
        appendLog("Base URL: " + baseUrl);

        try {
            currentApiAgent = new ApiHttpToolAgent(apiKey, model);
            apiSubscription = currentApiAgent.generate(baseUrl, nlPrompt).subscribe(
                    event -> {
                        // Log tool calls for visibility
                        event.functionCalls().forEach(fc ->
                            fc.name().ifPresent(toolName -> {
                                StringBuilder entry = new StringBuilder("[HTTP Tool] ").append(toolName);
                                fc.args().ifPresent(args -> {
                                    String path = args.containsKey("path") ? String.valueOf(args.get("path")) : null;
                                    if (path != null) entry.append(" ").append(path);
                                });
                                SwingUtilities.invokeLater(() -> appendLog(entry.toString()));
                            })
                        );
                        String text = event.stringifyContent();
                        if (text != null && !text.isBlank()) {
                            apiAgentOutput.append(text).append("\n");
                            SwingUtilities.invokeLater(() -> appendLog(text));
                        }
                    },
                    error -> SwingUtilities.invokeLater(() -> onApiError(error)),
                    ()    -> SwingUtilities.invokeLater(() -> onApiMode3Complete(projectLocation))
            );
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Failed to start API agent", ex);
            onApiError(ex);
        }
    }

    private void onApiMode3Complete(String projectLocation) {
        RxJavaPlugins.setErrorHandler(null);
        appendLog("\n--- Agent complete ---");

        List<ApiTestScenario> scenarios = ApiOutputParser.parse(apiAgentOutput.toString());
        if (scenarios.isEmpty()) {
            appendLog("WARNING: No test scenarios found in agent output.");
            appendLog("Check the log above and try again with a clearer prompt.");
            resetButtons();
            return;
        }

        appendLog("Parsed " + scenarios.size() + " scenario(s). Importing...");
        ApiTestStepImporter importer = new ApiTestStepImporter(sMainFrame);
        int count = importer.importAll(scenarios, msg -> appendLog(msg));

        appendLog("");
        appendLog("Done — " + count + " test case(s) imported.");
        resetButtons();
        if (count > 0) reloadProject(projectLocation);
    }

    private void onApiError(Throwable error) {
        RxJavaPlugins.setErrorHandler(null);
        LOG.log(Level.SEVERE, "API agent error", error);
        String msg = error.getMessage();
        if (msg == null) msg = error.getClass().getName();
        appendLog("ERROR: " + msg);
        if (error.getCause() != null) appendLog("  Caused by: " + error.getCause().getMessage());
        resetButtons();
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    private Properties loadAiSettings(String projectLocation) {
        return AISettings.loadFromProject(projectLocation);
    }

    private void reloadProject(String projectLocation) {
        try {
            sMainFrame.loadProject(projectLocation);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Reload project after import", ex);
        }
    }

    // ── Web generation (unchanged logic) ──────────────────────────────────────

    private void startWebGeneration() {
        String projectLocation = getProjectLocation();
        if (projectLocation == null) {
            Notification.show("Please open a project before generating tests.");
            return;
        }

        Properties aiProps = AISettings.loadFromProject(projectLocation);
        String apiKey  = aiProps.getProperty(AISettings.KEY_OPENROUTER_KEY, "").trim();
        String model   = aiProps.getProperty(AISettings.KEY_MODEL, AISettings.DEFAULT_MODEL).trim();
        String appUrl  = aiProps.getProperty(AISettings.KEY_APP_URL, "").trim();
        String npxPath = aiProps.getProperty(AISettings.KEY_NPX_PATH, AISettings.DEFAULT_NPX).trim();

        if (apiKey.isEmpty()) {
            Notification.show("Please configure your OpenRouter API key in Tools > AI Settings.");
            return;
        }
        if (appUrl.isEmpty()) appUrl = "(see prompt)";

        agentOutput.setLength(0);
        scenarioGroups.clear();
        logArea.setText("");
        importPanel.setVisible(false);
        generateBtn.setEnabled(false);
        cancelBtn.setText("Stop");

        RxJavaPlugins.setErrorHandler(ex -> SwingUtilities.invokeLater(() -> {
            Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
            onError(cause);
            RxJavaPlugins.setErrorHandler(null);
        }));

        appendLog("Starting generation with model: " + model);
        appendLog("npx command: " + npxPath);
        if (!appUrl.equals("(see prompt)")) appendLog("App URL: " + appUrl);

        currentAgent = new AITestGenerationAgent(apiKey, model, npxPath);

        List<String> existingReusables = detectExistingReusables(projectLocation);
        if (!existingReusables.isEmpty()) {
            appendLog("Existing reusable scenarios detected (will not be regenerated): "
                    + String.join(", ", existingReusables));
        }

        try {
            boolean isAzureMode = azureRadio.isSelected();
            io.reactivex.rxjava3.core.Flowable<Event> events;

            if (isAzureMode) {
                String azureUrl  = azureUrlField.getText().trim();
                String azurePat  = azurePatField.getText().trim();
                String azureProj = azureProjectField.getText().trim();
                String planId    = azurePlanIdField.getText().trim();
                if (azureUrl.isEmpty() || planId.isEmpty()) {
                    Notification.show("Please fill in the Azure DevOps Organization URL and Test Plan ID.");
                    resetButtons();
                    return;
                }
                appendLog("Mode: Azure Test Plan ID=" + planId);
                events = currentAgent.generateFromAzure(azureUrl, azurePat, azureProj, planId, appUrl,
                        existingReusables);
            } else {
                String prompt = promptArea.getText().trim();
                if (prompt.isEmpty()) {
                    Notification.show("Please enter a requirement or prompt.");
                    resetButtons();
                    return;
                }
                appendLog("Mode: Prompt");
                appendLog("Launching Playwright MCP server (first run may take ~30s to download)...");
                events = currentAgent.generateFromPrompt(prompt, appUrl, existingReusables);
            }

            appendLog("Agent started — waiting for response...");

            subscription = events.subscribe(
                    event -> {
                        event.functionCalls().forEach(fc ->
                            fc.name().ifPresent(toolName -> {
                                StringBuilder entry = new StringBuilder("[MCP Tool] ").append(toolName);
                                fc.args().ifPresent(args -> {
                                    String url      = args.containsKey("url")      ? String.valueOf(args.get("url"))      : null;
                                    String selector = args.containsKey("selector") ? String.valueOf(args.get("selector")) : null;
                                    String value    = args.containsKey("value")    ? String.valueOf(args.get("value"))    : null;
                                    if (url != null)      entry.append(" → ").append(url);
                                    if (selector != null) entry.append(" [").append(selector).append("]");
                                    if (value != null)    entry.append(" = ").append(value);
                                });
                                SwingUtilities.invokeLater(() -> appendLog(entry.toString()));
                            })
                        );
                        String text = event.stringifyContent();
                        if (text != null && !text.isBlank()) {
                            agentOutput.append(text).append("\n");
                            SwingUtilities.invokeLater(() -> appendLog(text));
                        }
                    },
                    error -> SwingUtilities.invokeLater(() -> onError(error)),
                    ()    -> SwingUtilities.invokeLater(this::onWebGenerationComplete)
            );

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Error starting AI generation", ex);
            onError(ex);
        }
    }

    private void handleCancel() {
        RxJavaPlugins.setErrorHandler(null);
        boolean stopped = false;

        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            if (currentAgent != null) currentAgent.cancel();
            stopped = true;
        }
        if (apiSubscription != null && !apiSubscription.isDisposed()) {
            apiSubscription.dispose();
            if (currentApiAgent != null) currentApiAgent.cancel();
            stopped = true;
        }

        if (stopped) {
            appendLog("Generation cancelled.");
            resetButtons();
        } else {
            setVisible(false);
            dispose();
        }
    }

    private void onError(Throwable error) {
        RxJavaPlugins.setErrorHandler(null);
        LOG.log(Level.SEVERE, "AI generation error", error);
        String msg = error.getMessage();
        if (msg == null) msg = error.getClass().getName();
        appendLog("ERROR: " + msg);
        if (error.getCause() != null) appendLog("  Caused by: " + error.getCause().getMessage());
        resetButtons();
    }

    private void onWebGenerationComplete() {
        RxJavaPlugins.setErrorHandler(null);
        appendLog("\n--- Generation complete ---");

        List<AITestGenerationAgent.ScenarioGroup> groups =
                AITestGenerationAgent.extractScenarioGroups(agentOutput.toString());

        if (groups.isEmpty()) {
            appendLog("WARNING: Could not find any valid Playwright Java code in agent output.");
            appendLog("Check the log above and try again with a clearer requirement.");
            resetButtons();
            return;
        }

        // Recovery: if a [REUSABLE] group has >1 test case, the LLM collapsed functional
        // test cases into the reusable block. Keep only the first TC as reusable and
        // promote the rest into a new regular scenario so they land in TestPlan, not ReusableComponents.
        List<AITestGenerationAgent.ScenarioGroup> fixed = new ArrayList<>();
        for (AITestGenerationAgent.ScenarioGroup g : groups) {
            if (g.reusable && g.testCases.size() > 1) {
                appendLog("NOTE: '" + g.scenarioName + "' [REUSABLE] had " + g.testCases.size()
                        + " test cases - keeping first as reusable, promoting the rest to a regular scenario.");
                fixed.add(new AITestGenerationAgent.ScenarioGroup(
                        g.scenarioName,
                        java.util.Collections.singletonList(g.testCases.get(0)),
                        true));
                fixed.add(new AITestGenerationAgent.ScenarioGroup(
                        g.scenarioName + "_Tests",
                        g.testCases.subList(1, g.testCases.size()),
                        false));
            } else {
                fixed.add(g);
            }
        }
        groups = fixed;

        scenarioGroups.clear();
        scenarioGroups.addAll(groups);

        int totalTestCases = groups.stream().mapToInt(g -> g.testCases.size()).sum();
        long reusableCount = groups.stream().filter(g -> g.reusable).count();
        appendLog("Generated " + groups.size() + " scenario(s) (" + reusableCount
                + " reusable), " + totalTestCases + " test case(s):");
        StringBuilder names = new StringBuilder();
        for (AITestGenerationAgent.ScenarioGroup g : groups) {
            String label = g.reusable ? " [REUSABLE]" : "";
            appendLog("  Scenario: " + g.scenarioName + label
                    + " (" + g.testCases.size() + " test cases)");
            g.testCases.forEach(tc -> appendLog("    • " + tc.name));
            names.append(g.scenarioName).append(label).append("\n");
        }

        scenarioCountLabel.setText(groups.size() + " scenario(s), " + totalTestCases
                + " test case(s) total — edit scenario names below if needed:");
        scenarioNamesArea.setText(names.toString().trim());

        resetButtons();
        importPanel.setVisible(true);
        revalidate();
        repaint();
    }

    // =========================================================================
    // Web import (unchanged)
    // =========================================================================

    private void importWebSteps() {
        String[] editedNames = scenarioNamesArea.getText().trim().split("\\n");
        if (editedNames.length == 0 || (editedNames.length == 1 && editedNames[0].isBlank())) {
            Notification.show("Please enter at least one scenario name.");
            return;
        }
        if (editedNames.length < scenarioGroups.size()) {
            Notification.show("Number of names (" + editedNames.length + ") is less than generated scenarios ("
                    + scenarioGroups.size() + "). Please provide a name for each.");
            return;
        }

        String projectLocation = getProjectLocation();
        if (projectLocation == null) {
            Notification.show("No project is open.");
            return;
        }

        File recordingDir = new File(projectLocation + File.separator + "Recording");
        recordingDir.mkdirs();

        // Determine the execute-reusable reference from the first reusable scenario.
        // This is used to prepend "Execute|Login:Login" as step 1 in every regular TC,
        // mirroring how API tests reference their Auth reusable via "Execute|Auth:Login".
        String executeReusable = null;
        for (int i = 0; i < scenarioGroups.size(); i++) {
            AITestGenerationAgent.ScenarioGroup g = scenarioGroups.get(i);
            if (g.reusable && !g.testCases.isEmpty()) {
                String rawR = editedNames[i].trim().replaceAll("(?i)\\s*\\[REUSABLE]\\s*", "").trim();
                String folder = rawR.replaceAll("[^A-Za-z0-9_\\-]", "_");
                if (folder.isEmpty()) folder = g.scenarioName;
                String tcName = g.testCases.get(0).name.replaceAll("[^A-Za-z0-9_\\-]", "_");
                executeReusable = folder + ":" + tcName;
                appendLog("Reusable login reference for Execute step: " + executeReusable);
                break;
            }
        }

        PlaywrightRecordingParser parser = new PlaywrightRecordingParser(sMainFrame);
        int importedTestCases = 0;

        for (int i = 0; i < scenarioGroups.size(); i++) {
            AITestGenerationAgent.ScenarioGroup group = scenarioGroups.get(i);

            // Strip any " [REUSABLE]" label the user may have left in the name field
            String rawName = editedNames[i].trim().replaceAll("(?i)\\s*\\[REUSABLE]\\s*", "").trim();
            String scenarioFolder = rawName.replaceAll("[^A-Za-z0-9_\\-]", "_");
            if (scenarioFolder.isEmpty()) scenarioFolder = group.scenarioName;

            if (group.reusable) {
                appendLog("[REUSABLE] Importing into ReusableComponents: " + scenarioFolder);
            }

            for (AITestGenerationAgent.TestScript tc : group.testCases) {
                String safeName = tc.name.replaceAll("[^A-Za-z0-9_\\-]", "_");
                File dest = new File(recordingDir, scenarioFolder + "_" + safeName + ".txt");
                try {
                    Files.writeString(dest.toPath(), tc.code);
                    // Regular TCs get an Execute step prepended to call the Login reusable,
                    // matching the API test pattern where step 1 is always Execute|Auth:Login.
                    String prependExec = (!group.reusable && executeReusable != null) ? executeReusable : null;
                    parser.playwrightParser(dest, scenarioFolder, safeName, group.reusable, prependExec);
                    importedTestCases++;
                    String target = group.reusable ? "ReusableComponents" : "TestPlan";
                    appendLog("Imported: " + target + "/" + scenarioFolder + "/" + safeName
                            + (prependExec != null ? " [Execute:" + prependExec + " prepended]" : ""));
                } catch (IOException ex) {
                    LOG.log(Level.SEVERE, "Failed to import " + scenarioFolder + "/" + safeName, ex);
                    appendLog("ERROR importing " + scenarioFolder + "/" + safeName + ": " + ex.getMessage());
                }
            }
        }

        if (importedTestCases > 0) {
            try {
                sMainFrame.loadProject(projectLocation);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "loadProject after import", ex);
            }
            appendLog(importedTestCases + " test case(s) imported successfully.");
        }

        setVisible(false);
        dispose();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Scans the project's ReusableComponents directory for scenario folders created by
     * previous [REUSABLE] imports. The folder names are returned as-is and passed to the
     * agent so it does not regenerate login or other shared setups that already exist.
     *
     * This mirrors the API test pattern where reusable scenarios live in ReusableComponents/
     * rather than TestPlan/.
     */
    private List<String> detectExistingReusables(String projectLocation) {
        List<String> names = new ArrayList<>();
        File reusableDir = new File(projectLocation + File.separator + "ReusableComponents");
        if (!reusableDir.exists()) return names;
        File[] subdirs = reusableDir.listFiles(File::isDirectory);
        if (subdirs == null) return names;
        for (File dir : subdirs) {
            names.add(dir.getName());
        }
        return names;
    }

    private void appendLog(String text) {
        logArea.append(text);
        if (!text.endsWith("\n")) logArea.append("\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void resetButtons() {
        generateBtn.setEnabled(true);
        cancelBtn.setText("Cancel");
    }

    private String getProjectLocation() {
        if (sMainFrame != null && sMainFrame.getProject() != null) {
            return sMainFrame.getProject().getLocation();
        }
        return null;
    }
}
