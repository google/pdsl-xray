package com.google.pdsl.xray.provider;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.engine.descriptor.PdslExecutable;
import org.junit.jupiter.engine.descriptor.PdslSharedInvocationContextProvider;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An invocation context provider that filters PDSL test executions based on Xray test plans.
 * 
 * <p>This class extends {@link PdslSharedInvocationContextProvider} to generate invocation contexts
 * for a PDSL test, but filters the results to only include test cases that are present in a
 * given Xray test plan (unless they are not tagged with an Xray test case ID, in which case they
 * are included by default).
 * 
 * <p>It uses reflection to access the underlying {@link PdslExecutable} within the JUnit context
 * to retrieve the tags and determine if a test case matches the filter criteria.
 */
public abstract class PdslXrayFilterInvocationContextProvider extends PdslSharedInvocationContextProvider {

    /**
     * Generates a collection of filtered invocation contexts for a test template.
     *
     * <p>This method first retrieves the original contexts from the superclass and then filters
     * them by evaluating the tags of each test executable. Tests tagged with an Xray test case ID
     * that do not belong to the provided test plan will be filtered out. Tests lacking an Xray
     * test case ID are included.
     *
     * @param parameter an {@link XrayPdslConfigParameter} containing both the original configuration
     *                  parameter and the list of test cases in the current test plan
     * @return a filtered collection of {@link TestTemplateInvocationContext} instances
     */
    public Collection<TestTemplateInvocationContext> getInvocationContext(XrayPdslConfigParameter parameter) {
        Collection<TestTemplateInvocationContext> originalContexts = super.getInvocationContext(parameter.pdslConfigParameter());
        List<String> testsInTestPlan = parameter.testsInTestPlan();

        return originalContexts.stream().filter(context -> {
            try {
                // Access the package-private executable field inside PdslInvocationContext
                Field executableField = context.getClass().getDeclaredField("executable");
                executableField.setAccessible(true);
                PdslExecutable executable = (PdslExecutable) executableField.get(context);

                String testCaseId = null;
                if (executable.getSharedTestCase() != null && executable.getSharedTestCase().isPresent()) {
                    testCaseId = executable.getSharedTestCase().get().getTags().stream()
                            .filter(tag -> tag.startsWith("@xray-test-case="))
                            .findFirst()
                            .map(tag -> tag.substring("@xray-test-case=".length()))
                            .orElse(null);
                }

                // If there's no tag, or the tag is in the test plan, include it
                return testCaseId == null || testsInTestPlan.contains(testCaseId);
            } catch (Exception e) {
                // Keep the test if reflection fails
                return true;
            }
        }).collect(Collectors.toList());
    }
}
