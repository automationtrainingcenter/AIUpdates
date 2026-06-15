package com.ing.ide.main.apigeneration;

/** One row in a generated API test case — maps directly to one TestStep. */
public class ApiTestRow {

    public final String action;
    public final String input;
    public final String condition;
    public final String description;

    public ApiTestRow(String action, String input, String condition, String description) {
        this.action      = action      != null ? action      : "";
        this.input       = input       != null ? input       : "";
        this.condition   = condition   != null ? condition   : "";
        this.description = description != null ? description : "";
    }

    public ApiTestRow(String action, String input, String condition) {
        this(action, input, condition, "");
    }

    public ApiTestRow(String action, String input) {
        this(action, input, "", "");
    }
}
