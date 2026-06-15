package com.ing.ide.main.apigeneration;

import java.util.List;

/** One generated test case — a name plus an ordered list of steps. */
public class ApiTestCase {

    public final String name;
    public final List<ApiTestRow> steps;

    public ApiTestCase(String name, List<ApiTestRow> steps) {
        this.name  = name;
        this.steps = steps;
    }
}
