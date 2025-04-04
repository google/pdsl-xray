@feature_tag1 @feature_tag2
  @feature_tag3 @xray-test-plan=GFENG-44108 @xray-test-plan=GFENG-43264
Feature: Minimal Scenario Outline

@scenario_tag1 @scenario_tag2
  @scenario_tag3 @xray-test-case=GFENG-45536
  @ios @android
Scenario: minimalistic
    Given the minimalism

@so_tag1  @so_tag2  
  @so_tag3 @xray-test-case=GFENG-45537
Scenario Outline: minimalistic outline
    Given the <what>

@ex_tag1 @ex_tag2
  @ex_tag3 @ios
Examples: 
  | what       | platform |
  | platform | ios      |

@ex_tag4 @ex_tag5
  @ex_tag6 @android
Examples: 
  | what            |
  | more minimalism |

@comment_tag1 @xray-test-case=GFENG-45538 #a comment
Scenario: comments
  Given a comment

@comment_tag#2 #a comment
Scenario: hash in tags
  Given a comment is preceded by a space

@joined_tag3@joined_tag4
Scenario: joined tags
  Given the @delimits tags


Scenario: tabular
  Given xray can update based on table values "<xray-test-case>"

  @ios
  Examples:
    |xray-test-plan | xray-test-case |  xray-test-platform  | xray-test-env |
    | GFENG-45678   | GFENG-45539    |  ios                 | PRD           |
    | GFENG-56789   | GFENG-46453    |  ios                 | PRD           |

  @android
  Examples:
    |xray-test-plan | xray-test-case |  xray-test-platform  | xray-test-env |
    | GFENG-12345   | GFENG-46453    |  android             | PRD           |