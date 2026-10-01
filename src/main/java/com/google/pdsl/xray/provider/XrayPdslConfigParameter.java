package com.google.pdsl.xray.provider;

import org.junit.jupiter.engine.descriptor.PdslConfigParameter;
import java.util.List;

/**
 * Wrapper for {@link PdslConfigParameter} that includes the list of test cases from the test plan.
 * 
 * <p>Note: {@code PdslConfigParameter} has a private constructor and cannot be directly extended,
 * so this class wraps it to provide the additional context needed for Xray test filtering.
 *
 * @param pdslConfigParameter the underlying PDSL configuration parameter
 * @param testsInTestPlan     a list of Xray test case IDs included in the current test plan
 */
public record XrayPdslConfigParameter(PdslConfigParameter pdslConfigParameter, List<String> testsInTestPlan) {
}
