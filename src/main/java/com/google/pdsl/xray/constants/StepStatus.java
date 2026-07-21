package com.google.pdsl.xray.constants;

import java.util.Arrays;
import java.util.List;

public enum StepStatus {
    EXECUTING,
    FAILED,
    BLOCKED,
    PASSED,
    TODO;

    public static List<String> getStringStatuses() {
        return Arrays.stream(values()).map(Enum::toString).toList();
    }
}