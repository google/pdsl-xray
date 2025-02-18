package com.pdsl.xray.models;

import java.util.List;

public record Info(String summary, String description, String testPlanKey,
                   List<String> testEnvironments, String user) {

}
