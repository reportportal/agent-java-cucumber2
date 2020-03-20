package com.epam.reportportal.cucumber;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.ReportPortalClient;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRS;
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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import rp.com.google.common.collect.Lists;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

/**
 * @author <a href="mailto:ihar_kahadouski@epam.com">Ihar Kahadouski</a>
 */
public class ParametersTest {

	private StepReporter stepReporter;

	@Mock
	private ReportPortalClient reportPortalClient;

	@Mock
	private ListenerParameters listenerParameters;

	@Mock
	private RunningContext.FeatureContext featureContext;

	@Mock
	private RunningContext.ScenarioContext scenarioContext;

	@Before
	public void initLaunch() {
		MockitoAnnotations.initMocks(this);
		when(listenerParameters.getEnable()).thenReturn(true);
		when(listenerParameters.getBaseUrl()).thenReturn("http://example.com");
		when(listenerParameters.getIoPoolSize()).thenReturn(10);
		when(listenerParameters.getBatchLogsSize()).thenReturn(5);
		stepReporter = new StepReporter() {
			@Override
			protected ReportPortal buildReportPortal() {
				return ReportPortal.create(reportPortalClient, listenerParameters);
			}
		};
		stepReporter.currentFeatureContext = featureContext;
		stepReporter.currentScenarioContext = scenarioContext;
	}

	@Test
	public void verifyClientRetrievesParametersFromRequest() {
		when(reportPortalClient.startLaunch(any(StartLaunchRQ.class))).then(t -> Maybe.create(emitter -> {
			StartLaunchRS rs = new StartLaunchRS();
			rs.setId("launchId");
			emitter.onSuccess(rs);
			emitter.onComplete();
		}).cache());

		stepReporter.startLaunch();

		ArrayList<String> parameterValues = Lists.newArrayList("1", "parameter");
		ArrayList<String> parameterNames = Lists.newArrayList("count", "item");

		PickleTestStep testStep = getPickleTestStep(parameterValues);

		when(scenarioContext.getId()).thenReturn(Maybe.create(emitter -> {
			emitter.onSuccess("scenarioId");
			emitter.onComplete();
		}));
		when(scenarioContext.getStep(testStep)).thenReturn(new Step(new Location(1, 1),
				"keyword",
				String.format("tesst with parameters <%s>", String.join("> <", parameterNames)),
				null
		));
		when(scenarioContext.getStepPrefix()).thenReturn("");

		stepReporter.beforeStep(testStep);

		ArgumentCaptor<StartTestItemRQ> startTestItemRQArgumentCaptor = ArgumentCaptor.forClass(StartTestItemRQ.class);
		verify(reportPortalClient, times(1)).startTestItem(anyString(), startTestItemRQArgumentCaptor.capture());

		StartTestItemRQ request = startTestItemRQArgumentCaptor.getValue();
		assertNotNull(request);
		assertNotNull(request.getParameters());
		assertEquals(2, request.getParameters().size());
		assertTrue(request.getParameters()
				.stream()
				.allMatch(it -> parameterValues.contains(it.getValue()) && parameterNames.contains(it.getKey())));
	}

	private PickleTestStep getPickleTestStep(List<String> parameterValues) {
		return new PickleTestStep(null,
				new PickleStep("text", Collections.emptyList(), Collections.emptyList()),
				new StepDefinitionMatch(parameterValues.stream()
						.map(it -> new Argument(RandomUtils.nextInt(), it))
						.collect(Collectors.toList()), getStepDefinition(), null, null, null)
		);
	}

	private StepDefinition getStepDefinition() {
		return new StepDefinition() {
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
		};
	}
}
