package com.google.pdsl.xray.core.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public record TestItem(String title, String testKey, String status, String testPlanKey, String testExecutionKey,
                       Set<String> environments,
                       List<String> stepDescription,
                       Throwable throwable,
                       int failedStepIndex) {

    public Optional<Integer> getFailedStepIndex() {
        return failedStepIndex >= 0 ? Optional.of(failedStepIndex) : Optional.empty();
    }

    public Optional<Throwable> getThrowable() {
        return Optional.ofNullable(throwable);
    }

}