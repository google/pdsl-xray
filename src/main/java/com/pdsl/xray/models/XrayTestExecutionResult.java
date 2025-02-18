package com.pdsl.xray.models;

import java.util.List;

public record XrayTestExecutionResult(Info info, List<XrayTestResult> tests) {


}