Feature: Tabular Parameters
  In addition to annotations, XRAY metadata can be provided via the plugin
  as parameters in tabular format

  Scenario: Parameters in Examples Table
    Given a test is run with the PDSL-XRAY plugin
    When the XRAY metadata is passed in the examples table
    Then the plugin updates XRAY with the appropriate metadata

  @ios
    Examples:
      |xray-test-plan | xray-test-case |  xray-test-platform  | xray-test-env |
      | GFENG-43264   | GFENG-45539    |  ios                 | DEV,TST       |
      | GFENG-43264   | GFENG-46453    |  ios                 | DEV,TST       |

  @android
    Examples:
      |xray-test-plan | xray-test-case |  xray-test-platform  | xray-test-env |
      | GFENG-43264   | GFENG-46453    |  android             | DEV,TST       |