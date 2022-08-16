Feature: Belly

  @ok
  @issue:JIRA-1234
  @issue:JIRA-5678
  Scenario: a few cukes
    Given I have 42 cukes in my belly
    When I wait 1 hour
    Then my belly should growl
