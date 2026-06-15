package com.ing.ide.main.settings;

import com.ing.ide.main.mainui.AppMainFrame;
import com.ing.ide.util.Notification;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.UIManager;

/**
 * Settings dialog for the AI Test Generator feature.
 * Stores configuration in {project}/Settings/AISettings.properties.
 */
public class AISettings extends JFrame {

    public static final String KEY_OPENROUTER_KEY = "ai.openrouter.key";
    public static final String KEY_MODEL = "ai.model";
    public static final String KEY_APP_URL = "ai.app.url";
    public static final String KEY_NPX_PATH = "ai.npx.path";

    public static final String DEFAULT_MODEL = "anthropic/claude-3.5-sonnet";
    public static final String DEFAULT_NPX = System.getProperty("os.name", "")
            .toLowerCase().contains("win") ? "npx.cmd" : "npx";

    private final AppMainFrame sMainFrame;

    private JPasswordField openRouterKeyField;
    private JTextField modelField;
    private JTextField appUrlField;
    private JTextField npxPathField;

    public AISettings(AppMainFrame sMainFrame) {
        this.sMainFrame = sMainFrame;
        setTitle("AI Test Generator Settings");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setResizable(false);
        initUI();
        pack();
    }

    private void initUI() {
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(6, 0, 6, 12);
        lc.gridx = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(6, 0, 6, 0);
        fc.gridx = 1;

        Font labelFont = UIManager.getFont("Label.font");

        // --- OpenRouter API Key ---
        lc.gridy = 0; fc.gridy = 0;
        JLabel keyLabel = new JLabel("OpenRouter API Key:");
        keyLabel.setFont(labelFont);
        mainPanel.add(keyLabel, lc);
        openRouterKeyField = new JPasswordField(36);
        openRouterKeyField.setPreferredSize(new Dimension(320, 26));
        mainPanel.add(openRouterKeyField, fc);

        // --- Model ---
        lc.gridy = 1; fc.gridy = 1;
        JLabel modelLabel = new JLabel("Model (OpenRouter ID):");
        modelLabel.setFont(labelFont);
        mainPanel.add(modelLabel, lc);
        modelField = new JTextField(DEFAULT_MODEL, 36);
        mainPanel.add(modelField, fc);

        // --- App Base URL ---
        lc.gridy = 2; fc.gridy = 2;
        JLabel urlLabel = new JLabel("App Base URL:");
        urlLabel.setFont(labelFont);
        mainPanel.add(urlLabel, lc);
        appUrlField = new JTextField("https://", 36);
        mainPanel.add(appUrlField, fc);

        // --- npx path ---
        lc.gridy = 3; fc.gridy = 3;
        JLabel npxLabel = new JLabel("npx command / path:");
        npxLabel.setFont(labelFont);
        mainPanel.add(npxLabel, lc);
        npxPathField = new JTextField(DEFAULT_NPX, 36);
        mainPanel.add(npxPathField, fc);

        // Hint
        lc.gridy = 4; fc.gridy = 4;
        lc.gridwidth = 2; fc.gridwidth = 2;
        JLabel hint = new JLabel("<html><i>Requires Node.js 18+ installed for MCP servers.</i></html>");
        hint.setFont(labelFont.deriveFont(Font.ITALIC, 11f));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        mainPanel.add(hint, lc);

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton saveBtn = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");
        saveBtn.addActionListener(e -> save());
        cancelBtn.addActionListener(e -> setVisible(false));
        btnPanel.add(saveBtn);
        btnPanel.add(cancelBtn);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(mainPanel, BorderLayout.CENTER);
        getContentPane().add(btnPanel, BorderLayout.SOUTH);
    }

    public void open() {
        load();
        setLocationRelativeTo(sMainFrame);
        setVisible(true);
    }

    public void load() {
        Properties props = loadProps();
        openRouterKeyField.setText(props.getProperty(KEY_OPENROUTER_KEY, ""));
        modelField.setText(props.getProperty(KEY_MODEL, DEFAULT_MODEL));
        appUrlField.setText(props.getProperty(KEY_APP_URL, "https://"));
        npxPathField.setText(props.getProperty(KEY_NPX_PATH, DEFAULT_NPX));
    }

    private void save() {
        Properties props = new Properties();
        props.setProperty(KEY_OPENROUTER_KEY, new String(openRouterKeyField.getPassword()));
        props.setProperty(KEY_MODEL, modelField.getText().trim());
        props.setProperty(KEY_APP_URL, appUrlField.getText().trim());
        props.setProperty(KEY_NPX_PATH, npxPathField.getText().trim());
        try {
            File settingsDir = getSettingsDir();
            if (settingsDir != null) {
                settingsDir.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(
                        new File(settingsDir, "AISettings.properties"))) {
                    props.store(fos, "INGenious AI Test Generator Settings");
                }
                Notification.show("AI Settings saved.");
                setVisible(false);
            }
        } catch (IOException ex) {
            Notification.show("Failed to save AI Settings: " + ex.getMessage());
        }
    }

    /** Read settings from the current project's Settings directory. */
    public static Properties loadFromProject(String projectLocation) {
        Properties props = new Properties();
        File file = new File(projectLocation + File.separator + "Settings"
                + File.separator + "AISettings.properties");
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException ignored) {
            }
        }
        return props;
    }

    private Properties loadProps() {
        String loc = getProjectLocation();
        return loc != null ? loadFromProject(loc) : new Properties();
    }

    private File getSettingsDir() {
        String loc = getProjectLocation();
        return loc == null ? null : new File(loc + File.separator + "Settings");
    }

    private String getProjectLocation() {
        if (sMainFrame != null && sMainFrame.getProject() != null) {
            return sMainFrame.getProject().getLocation();
        }
        return null;
    }
}
