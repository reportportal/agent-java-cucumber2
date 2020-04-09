package com.epam.reportportal.cucumber;

import com.epam.reportportal.annotations.TestCaseId;
import com.epam.reportportal.annotations.TestCaseIdKey;
import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.ReportPortalClient;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRS;
import cucumber.api.TestStep;
import cucumber.runner.PickleTestStep;
import cucumber.runtime.Argument;
import cucumber.runtime.ParameterInfo;
import cucumber.runtime.StepDefinition;
import cucumber.runtime.StepDefinitionMatch;
import gherkin.ast.Location;
import gherkin.ast.Step;
import gherkin.pickles.PickleStep;
import io.reactivex.Maybe;
import org.apache.commons.lang3.RandomUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import rp.com.google.common.collect.Lists;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.epam.reportportal.cucumber.TestCaseIdTest.StepReporterExtension.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * @author <a href="mailto:ivan_budayeu@epam.com">Ivan Budayeu</a>
 */
public class TestCaseIdTest {

	private StepReporter stepReporter;

	private static ArrayList<String> parameterValues = Lists.newArrayList("1", "parameter");
	private static ArrayList<String> parameterNames = Lists.newArrayList("count", "item");

	public static class StepReporterExtension extends StepReporter {

		static final ThreadLocal<Step> STEP = new ThreadLocal<>();

		static final ThreadLocal<TestStep> TEST_STEP = new ThreadLocal<>();

		static final ThreadLocal<RunningContext.FeatureContext> FEATURE_CONTEXT = new ThreadLocal<>();
		static final ThreadLocal<RunningContext.ScenarioContext> SCENARIO_CONTEXT = new ThreadLocal<>();

		static final ThreadLocal<ReportPortal> REPORT_PORTAL = new ThreadLocal<>();

		static final ThreadLocal<Launch> LAUNCH = new ThreadLocal<>();

		static final ThreadLocal<ReportPortalClient> REPORT_PORTAL_CLIENT = new ThreadLocal<>();

		static final ThreadLocal<ListenerParameters> LISTENER_PARAMETERS = new ThreadLocal<>();

		public StepReporterExtension() {
			STEP.set(getStep());

			RunningContext.FeatureContext featureContext = mock(RunningContext.FeatureContext.class);
			FEATURE_CONTEXT.set(featureContext);
			currentFeatureContext = FEATURE_CONTEXT.get();

			RunningContext.ScenarioContext scenarioContext = mock(RunningContext.ScenarioContext.class);
			when(scenarioContext.getId()).thenReturn(Maybe.create(emitter -> {
				emitter.onSuccess("scenarioId");
				emitter.onComplete();
			}));

			when(scenarioContext.getStepPrefix()).thenReturn("");
			SCENARIO_CONTEXT.set(scenarioContext);
			currentScenarioContext = SCENARIO_CONTEXT.get();

			ReportPortalClient reportPortalClient = mock(ReportPortalClient.class);
			when(reportPortalClient.startLaunch(any(StartLaunchRQ.class))).then(t -> Maybe.create(emitter -> {
				StartLaunchRS rs = new StartLaunchRS();
				rs.setId("launchId");
				emitter.onSuccess(rs);
				emitter.onComplete();
			}).blockingGet());
			REPORT_PORTAL_CLIENT.set(reportPortalClient);

			ListenerParameters listenerParameters = mock(ListenerParameters.class);
			when(listenerParameters.getEnable()).thenReturn(true);
			when(listenerParameters.getBaseUrl()).thenReturn("http://example.com");
			when(listenerParameters.getIoPoolSize()).thenReturn(10);
			when(listenerParameters.getBatchLogsSize()).thenReturn(5);
			LISTENER_PARAMETERS.set(listenerParameters);

			Launch launch = mock(Launch.class);
			when(launch.start()).thenReturn(Maybe.create(emitter -> {
				emitter.onSuccess("launchId");
				emitter.onComplete();
			}));
			LAUNCH.set(launch);

			ReportPortal reportPortal = mock(ReportPortal.class);
			when(reportPortal.getClient()).thenReturn(REPORT_PORTAL_CLIENT.get());
			when(reportPortal.newLaunch(any())).thenReturn(LAUNCH.get());
			when(reportPortal.getParameters()).thenReturn(LISTENER_PARAMETERS.get());
			REPORT_PORTAL.set(reportPortal);
		}

		@Override
		protected ReportPortal buildReportPortal() {
			return REPORT_PORTAL.get();
		}

		private static Step getStep() {
			Location location = new Location(1, 1);
			return new Step(location, "Given", "Parametrized", null);
		}

	}

	public static class StepDefinitionExtension implements StepDefinition {

		private final Method method;

		public StepDefinitionExtension(final Method method) {
			this.method = method;
		}

		@Override
		public List<Argument> matchedArguments(PickleStep step) {
			return null;
		}

		@Override
		public String getLocation(boolean detail) {
			return "com.test.Parametrized(int,String)";
		}

		@Override
		public Integer getParameterCount() {
			return null;
		}

		@Override
		public ParameterInfo getParameterType(int n, Type argumentType) throws IndexOutOfBoundsException {
			return null;
		}

		@Override
		public void execute(String language, Object[] args) throws Throwable {

		}

		@Override
		public boolean isDefinedAt(StackTraceElement stackTraceElement) {
			return false;
		}

		@Override
		public String getPattern() {
			return null;
		}

		@Override
		public boolean isScenarioScoped() {
			return false;
		}
	}

	@Before
	public void initLaunch() {
		stepReporter = new StepReporterExtension();
	}

	@Test
	public void shouldSendCaseIdWhenNotParametrized() throws NoSuchMethodException {

		stepReporter.startLaunch();

		PickleTestStep testStep = getCustomTestStep(new StepDefinitionExtension(this.getClass()
				.getDeclaredMethod("testCaseIdMethod")));

		TEST_STEP.set(testStep);

		when(SCENARIO_CONTEXT.get().getStep(TEST_STEP.get())).thenReturn(new Step(new Location(1, 1),
				"keyword",
				String.format("tesst with parameters <%s>", String.join("> <", parameterNames)),
				null
		));

		stepReporter.beforeStep(TEST_STEP.get());

		ArgumentCaptor<StartTestItemRQ> startTestItemRQArgumentCaptor = ArgumentCaptor.forClass(StartTestItemRQ.class);
		verify(LAUNCH.get(), times(1)).startTestItem(any(), startTestItemRQArgumentCaptor.capture());

		StartTestItemRQ request = startTestItemRQArgumentCaptor.getValue();
		assertNotNull(request);
		assertNotNull(request.getTestCaseId());
		assertEquals("SimpleCaseId", request.getTestCaseId());
	}

	@TestCaseId(value = "SimpleCaseId")
	public void testCaseIdMethod() {

	}

	@Test
	public void shouldSendCaseIdWhenParametrized() throws NoSuchMethodException {

		stepReporter.startLaunch();

		PickleTestStep testStep = getCustomTestStep(new StepDefinitionExtension(this.getClass()
				.getDeclaredMethod("testCaseIdParametrizedMethod", Integer.class, String.class)));

		TEST_STEP.set(testStep);

		when(SCENARIO_CONTEXT.get().getStep(TEST_STEP.get())).thenReturn(new Step(new Location(1, 1),
				"keyword",
				String.format("tesst with parameters <%s>", String.join("> <", parameterNames)),
				null
		));

		stepReporter.beforeStep(TEST_STEP.get());

		ArgumentCaptor<StartTestItemRQ> startTestItemRQArgumentCaptor = ArgumentCaptor.forClass(StartTestItemRQ.class);
		verify(LAUNCH.get(), times(1)).startTestItem(any(), startTestItemRQArgumentCaptor.capture());

		StartTestItemRQ request = startTestItemRQArgumentCaptor.getValue();
		assertNotNull(request);
		assertNotNull(request.getTestCaseId());
		assertEquals("parameter", request.getTestCaseId());
	}

	@TestCaseId(parametrized = true)
	public void testCaseIdParametrizedMethod(Integer index, @TestCaseIdKey String caseIdKey) {

	}

	@Test
	public void shouldSendCaseIdWhenParametrizedWithoutKey() throws NoSuchMethodException {

		stepReporter.startLaunch();

		PickleTestStep testStep = getCustomTestStep(new StepDefinitionExtension(this.getClass()
				.getDeclaredMethod("testCaseIdParametrizedMethodWithoutKey", Integer.class, String.class)));

		TEST_STEP.set(testStep);

		when(SCENARIO_CONTEXT.get().getStep(TEST_STEP.get())).thenReturn(new Step(new Location(1, 1),
				"keyword",
				String.format("tesst with parameters <%s>", String.join("> <", parameterNames)),
				null
		));

		stepReporter.beforeStep(TEST_STEP.get());

		ArgumentCaptor<StartTestItemRQ> startTestItemRQArgumentCaptor = ArgumentCaptor.forClass(StartTestItemRQ.class);
		verify(LAUNCH.get(), times(1)).startTestItem(any(), startTestItemRQArgumentCaptor.capture());

		StartTestItemRQ request = startTestItemRQArgumentCaptor.getValue();
		assertNotNull(request);
		assertNotNull(request.getTestCaseId());
		assertEquals("com.test.Parametrized[1,parameter]", request.getTestCaseId());
	}

	@TestCaseId(parametrized = true)
	public void testCaseIdParametrizedMethodWithoutKey(Integer index, String caseIdKey) {

	}

	private PickleTestStep getCustomTestStep(StepDefinition stepDefinition) {
		return new PickleTestStep(null,
				new PickleStep("text", Collections.emptyList(), Collections.emptyList()),
				new StepDefinitionMatch(parameterValues.stream()
						.map(it -> new cucumber.runtime.Argument(RandomUtils.nextInt(), it))
						.collect(Collectors.toList()), stepDefinition, null, null, null)
		);
	}

}


