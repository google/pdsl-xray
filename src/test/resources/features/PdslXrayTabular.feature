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


     # Note that this could be made a tabular parameter, but since it's the same for everyone they're on the scenario
    @xray-test-plan=GFENG-43264
    @xray-test-case=GFENG-74303
    @android
    Scenario: XRAY Iterations
      XRAY test cases can be created with a set of test data, which provided
      permutations for specific test cases. Each of these iterations is technically
      part of one test case.

      In these instances there will not be a unique test case ID you can associate
      with the iteraiton as they all have the same one. This is handled instead by
      provide the XRAY iteration ID

      Given a test case is made in XRAY
      And that test case has permutations specified through test data
      And there is a test run with the data "<DATUM>"
      When the Iteration ID is provided
      Then the test case iteration is updated with the appropriate status

      Examples:
        |DATUM| xray-iteration |
        |Foo| 1                |
        |Bar| 2                |
        |Bizz| 3               |
        |Qux| 4                |
        |Quux| 5               |
        |Narf| 6               |
        |Snafu| 7              |
        |Spam| 8               |
        |Eggs| 9               |
        |Bazz| 10              |
        |Buzz| 11              |
        |Gralt| 12             |
