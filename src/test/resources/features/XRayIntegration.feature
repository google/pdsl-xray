@xray-test-plan=GFENG-43264
Feature: Minimal Scenario Outline

@xray-test-case=GFENG-45536
@xray-test-env=DEV,TST
@ios
Scenario: Tests can be demarcated by environment
    Given a scenario is marked with a single XRAY test case annotation
  But it is marked with multiple environment tags
  When the test is run
  Then a single test execution is produced
  And the execution contains all the environment tags

@xray-test-case=GFENG-43270
Scenario: Tags over Examples Table
  Given a test is written with two examples tables
  But the one that is executing is for the "<platform>" platform
  When each example table has an XRAY tag over it
  Then the XRAY meta data is extracted from the respective tags
  And the tags apply only to the Example block they were taken from

@ios @xray-test-case=GFENG-45537 @xray-test-env=TST
Examples: 
  | platform |
  | ios      |

@android @xray-test-case=GFENG-45537 @xray-test-env=DEV
Examples: 
  | platform            |
  | android             |
