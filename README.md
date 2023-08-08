# Cucumber2 Agent for ReportPortal

> **DISCLAIMER**: We use Google Analytics for sending anonymous usage information such as agent's and client's names,
> and their versions after a successful launch start. This information might help us to improve both ReportPortal
> backend and client sides. It is used by the ReportPortal team only and is not supposed for sharing with 3rd parties.

[![Maven Central](https://img.shields.io/maven-central/v/com.epam.reportportal/agent-java-cucumber2.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.epam.reportportal/agent-java-cucumber2)
[![CI Build](https://github.com/reportportal/agent-java-cucumber2/actions/workflows/ci.yml/badge.svg)](https://github.com/reportportal/agent-java-cucumber2/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/reportportal/agent-java-cucumber2/branch/develop/graph/badge.svg?token=RPBOBZJHCo)](https://codecov.io/gh/reportportal/agent-java-cucumber2)
[![Join Slack chat!](https://reportportal-slack-auto.herokuapp.com/badge.svg)](https://reportportal-slack-auto.herokuapp.com)
[![stackoverflow](https://img.shields.io/badge/reportportal-stackoverflow-orange.svg?style=flat)](http://stackoverflow.com/questions/tagged/reportportal)
[![Build with Love](https://img.shields.io/badge/build%20with-❤%EF%B8%8F%E2%80%8D-lightgrey.svg)](http://reportportal.io?style=flat)

### Installation

Add to POM.xml

**Dependency**

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
<dependency>
  <groupId>com.epam.reportportal</groupId>
  <artifactId>agent-java-cucumber2</artifactId>
  <version>5.1.5</version>
</dependency>
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

### Install Reporter

As Cucumber runs your features, it calls out to any number of listener objects to let them know
how it’s progressing. These listeners are notified at various points throughout the run of features.
This principle is used to notify ReportPortal about your tests progress in real-time.
ReportPortal supports two kinds of Reporters.
Both of them allow you to report your execution progress to ReportPortal,
but there are some differences in report structure.

* **Scenario Reporter**
* **Step Reporter**

Step Reporter propagates the most traditional for ReportPortal test structure keeping your scenarios and steps inside 
as separate entities. In opposite, Scenario Reporter use scenario as the base point and does not separate step from each
other which is sometimes more convenient for BDD users.

Enabling **StepReporter**:

```java

@RunWith(Cucumber.class)
@CucumberOptions(plugin = {"pretty", "com.epam.reportportal.cucumber.StepReporter"})
public class RunCukesTest {
}
```

Enabling **ScenarioReporter**:

```java

@RunWith(Cucumber.class)
@CucumberOptions(plugin = {"pretty", "com.epam.reportportal.cucumber.ScenarioReporter"})
public class RunCukesTest {
}
```

### Configuration

Copy you configuration from UI of ReportPortal at `User Profile` section

or

In order to start using of agent, user should configure property file
`reportportal.properties` in such format:

**reportportal.properties**

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
rp.endpoint = https://rp.epam.com/
rp.username = default
rp.api.key = 8967de3b-fec7-47bb-9dbc-2aa4ceab8b1e
rp.launch = default_TEST_EXAMPLE
rp.project = default_project

## OPTIONAL PARAMETERS
rp.attributes = TAG1;TAG2;key:value
rp.keystore.resource = reportportal-client-v2.jks
rp.keystore.password = reportportal
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

**Parameters**

User should provide next parameters to agent.

| **Parameter**        | **Description**                                                                                                                                                                                                                                                                                                                                | **Required** |
|----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|
| rp.enable            | Enable/Disable logging to ReportPortal: rp.enable=true - enable log to RP server.  Any other value means 'false': rp.enable=false - disable log to RP server.  If parameter is skipped in properties file then automation project results will be posted on RP.                                                                                | No           |
| rp.uuid              | API key of the user.                                                                                                                                                                                                                                                                                                                           | Yes          |
| rp.endpoint          | URL of web service, where requests should be sent                                                                                                                                                                                                                                                                                              | Yes          |
| rp.launch            | The unique name of Launch (Run), based on that name a history of runs will be created for the particular name                                                                                                                                                                                                                                  | Yes          |
| rp.project           | Project name, to identify scope                                                                                                                                                                                                                                                                                                                | Yes          |
| rp.tags              | Set of tags for specifying additional meta information for current launch. Format: tag1;tag2;build:12345-6. Tags should be separated by “;”. There are one special tag- build – it should be used for specification number of build for launch.                                                                                                | No           |
| rp.batch.size.logs   | In order to rise up performance and reduce number of requests to server                                                                                                                                                                                                                                                                        | Yes          |
| rp.keystore.resource | Put your JKS file into resources and specify path to it                                                                                                                                                                                                                                                                                        |              |
| rp.keystore.password | Access password for JKS (certificate storage) package, mentioned above                                                                                                                                                                                                                                                                         |              |
| rp.convertimage      | Colored log images can be converted to grayscale for reducing image size. Values: ‘true’ – will be converted. Any other value means false.                                                                                                                                                                                                     | No           |
| rp.mode              | ReportPortal provide possibility to specify visibility of executing launch. Currently supported two modes: DEFAULT  - all users from project can see this launch; DEBUG - only owner can see this launch(in debug sub tab). Note: for all java based clients(TestNg, Junit) mode will be set automaticaly to "DEFAULT" if it is not specified. | No           |
| rp.skipped.issue     | ReportPortal provide feature to mark skipped tests as not 'To Investigate' items on WS side. Parameter could be equal boolean values: *TRUE* - skipped tests considered as issues and will be mark as 'To Investigate' on portal. *FALSE* - skipped tests will not be mark as 'To Investigate' on portal.                                      | No           |

Launch name can be edited once, and should be edited once, before first
execution. As usual, parts of launches are fixed for a long time. Keeping the
same name for launch, here we will understand a fixed list of suites under
launch, will help to have a history trend, and on UI instances of the same
launch will be saved with postfix "\#number", like "Test Launch \#1", "Test
Launch \#2" etc.

> If mandatory properties are missed client throw exception
> IllegalArgumentException.

**Proxy configuration**

The client uses standard java proxy mechanism. If you are new
try [Java networking and proxies](<http://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html>) page.

Ways to set up properties:

a. reportportal.properties file
b. command line properties (-Dhttps.proxyHost=localhost)

**How to provide parameters**

There are two way to load parameters.

- Load from properties file

Properties file should have name: “reportportal.properties”. Properties file can
be situated on the class path (in the project directory).

If listener can’t find properties file it throws FileNotFoundException.

- Use system variables

**Parameters loading order**

Client loads properties in the next order. Every next level overrides previous:

a. Properties file. Client loads all known to him properties (specified in the
"Input Parameters" section) from "reportportal.properties" file.

b. Environment variables. If environment variables with names specified in the
"Input Parameters" section exist client reload existing properties from
environment variables.

c. JVM variables. If JVM variables with names specified in the
"Input Parameters" section exist, client overrides existing ones from
JVM variables.

#### Events

* TestRunStarted - the first event sent.
* TestSourceRead - sent for each feature file read, contains the feature file source.
* TestCaseStarted - sent before starting the execution of a Test Case(/Pickle/Scenario), contains the Test Case
* TestStepStarted - sent before starting the execution of a Test Step, contains the Test Step
* TestStepFinished - sent after the execution of a Test Step, contains the Test Step and its Result.
* TestCaseFinished - sent after the execution of a Test Case(/Pickle/Scenario), contains the Test Case and its Result.
* TestRunFinished - the last event sent.
* EmbedEvent - calling scenario.embed in a hook triggers this event.
* WriteEvent - calling scenario.write in a hook triggers this event.

#### Main methods

* setEventPublisher(EventPublisher publisher) - the only method that should be overridden to implement Formatter
  functionality
* beforeLaunch - manipulations before/after the launch is started
* afterLaunch - manipulations before/after the launch is finished
* beforeFeature - manipulations before/after new feature is started
* afterFeature - manipulations before/after current feature is finished
* beforeScenario - manipulations before/after new scenario is started
* afterScenario - manipulations before/after current scenario is finished
* startFeature - starting new feature
* startLaunch - starting new launch
* beforeStep - manipulations before/after new step is started
* afterStep - manipulations before/after current step is finished
* beforeHooks - manipulations before/after new hook is started
* afterHooks - manipulations before/after current hook is finished
