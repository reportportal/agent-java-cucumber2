package com.epam.reportportal.cucumber;

import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import cucumber.api.Result;
import cucumber.api.TestCase;
import cucumber.api.TestStep;
import cucumber.api.event.TestSourceRead;
import gherkin.AstBuilder;
import gherkin.Parser;
import gherkin.ParserException;
import gherkin.TokenMatcher;
import gherkin.ast.*;
import gherkin.pickles.PickleTag;
import io.reactivex.Maybe;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.epam.reportportal.cucumber.Utils.extractAttributes;
import static com.epam.reportportal.cucumber.Utils.extractPickleTags;

/**
 * Running context that contains mostly manipulations with Gherkin objects.
 * Keeps necessary information regarding current Feature, Scenario and Step
 *
 * @author Serhii Zharskyi
 */
public class RunningContext {

	public static class FeatureContext {

		private static final Map<String, TestSourceRead> PATH_TO_READ_EVENT_MAP = new ConcurrentHashMap<>();

		private String currentFeatureUri;
		private Maybe<String> currentFeatureId;
		private Feature currentFeature;
		private Set<ItemAttributesRQ> attributes;

		FeatureContext() {
			attributes = new HashSet<>();
		}

		static void addTestSourceReadEvent(String path, TestSourceRead event) {
			PATH_TO_READ_EVENT_MAP.put(path, event);
		}

		ScenarioContext getScenarioContext(TestCase testCase) {
			ScenarioDefinition scenario = getScenario(testCase);
			ScenarioContext context = new ScenarioContext();
			context.processScenario(scenario);
			context.setTestCase(testCase);
			context.processBackground(getBackground());
			context.processScenarioOutline(scenario);
			context.processTags(testCase.getTags());
			return context;
		}

		FeatureContext processTestSourceReadEvent(TestCase testCase) {
			TestSourceRead event = PATH_TO_READ_EVENT_MAP.get(testCase.getUri());
			currentFeature = getFeature(event.source);
			currentFeatureUri = event.uri;
			attributes = extractAttributes(currentFeature.getTags());
			return this;
		}

		Feature getFeature(String source) {
			Parser<GherkinDocument> parser = new Parser<>(new AstBuilder());
			TokenMatcher matcher = new TokenMatcher();
			GherkinDocument gherkinDocument;
			try {
				gherkinDocument = parser.parse(source, matcher);
			} catch (ParserException e) {
				// Ignore exceptions
				return null;
			}
			return gherkinDocument.getFeature();
		}

		Background getBackground() {
			ScenarioDefinition background = getFeature().getChildren().get(0);
			if (background instanceof Background) {
				return (Background) background;
			} else {
				return null;
			}
		}

		Feature getFeature() {
			return currentFeature;
		}

		Set<ItemAttributesRQ> getAttributes() {
			return attributes;
		}

		String getUri() {
			return currentFeatureUri;
		}

		Maybe<String> getFeatureId() {
			return currentFeatureId;
		}

		void setFeatureId(Maybe<String> featureId) {
			this.currentFeatureId = featureId;
		}

		@SuppressWarnings("unchecked")
		<T extends ScenarioDefinition> T getScenario(TestCase testCase) {
			List<ScenarioDefinition> featureScenarios = getFeature().getChildren();
			for (ScenarioDefinition scenario : featureScenarios) {
				if (scenario instanceof Background) {
					continue;
				}
				if (testCase.getLine() == scenario.getLocation().getLine() && testCase.getName().equals(scenario.getName())) {
					return (T) scenario;
				} else {
					if (scenario instanceof ScenarioOutline) {
						for (Examples example : ((ScenarioOutline) scenario).getExamples()) {
							for (TableRow tableRow : example.getTableBody()) {
								if (tableRow.getLocation().getLine() == testCase.getLine()) {
									return (T) scenario;
								}
							}
						}
					}
				}
			}
			throw new IllegalStateException("Scenario can't be null!");
		}
	}

	public static class ScenarioContext {

		private static final Map<ScenarioDefinition, List<Integer>> scenarioOutlineMap = new ConcurrentHashMap<>();
		private final Queue<Step> backgroundSteps = new ArrayDeque<>();
		private final Map<Integer, Step> scenarioLocationMap = new HashMap<>();
		private Set<ItemAttributesRQ> attributes = new HashSet<>();

		private Maybe<String> id;
		private Maybe<String> currentStepId;
		private Background background;
		private ScenarioDefinition scenario;
		private String scenarioDesignation;
		private TestCase testCase;
		private boolean hasBackground = false;
		private String outlineIteration;

		private Maybe<String> hookStepId;
		private Result.Type hookStatus;

		void processScenario(ScenarioDefinition scenario) {
			this.scenario = scenario;
			for (Step step : scenario.getSteps()) {
				scenarioLocationMap.put(step.getLocation().getLine(), step);
			}
		}

		void processBackground(Background background) {
			if (background != null) {
				this.background = background;
				hasBackground = true;
				backgroundSteps.addAll(background.getSteps());
				mapBackgroundSteps(background);
			}
		}

		void processScenarioOutline(ScenarioDefinition scenarioOutline) {
			if (isScenarioOutline(scenarioOutline)) {
				scenarioOutlineMap.computeIfAbsent(scenarioOutline,
						k -> ((ScenarioOutline) scenarioOutline).getExamples()
								.stream()
								.flatMap(e -> e.getTableBody().stream())
								.map(r -> r.getLocation().getLine())
								.collect(Collectors.toList())
				);
				int iterationIdx = IntStream.range(0, scenarioOutlineMap.get(scenarioOutline).size())
						.filter(i -> getLine() == scenarioOutlineMap.get(scenarioOutline).get(i))
						.findFirst()
						.orElseThrow(() -> new IllegalStateException(String.format("No outline iteration number found for scenario %s",
								scenarioDesignation
						)));
				outlineIteration = String.format("[%d]", iterationIdx + 1);
			}
		}

		void processTags(List<PickleTag> pickleTags) {
			attributes = extractPickleTags(pickleTags);
		}

		void mapBackgroundSteps(Background background) {
			for (Step step : background.getSteps()) {
				scenarioLocationMap.put(step.getLocation().getLine(), step);
			}
		}

		String getName() {
			return scenario.getName();
		}

		String getKeyword() {
			return scenario.getKeyword();
		}

		int getLine() {
			if (isScenarioOutline(scenario)) {
				return testCase.getLine();
			}
			return scenario.getLocation().getLine();
		}

		Set<ItemAttributesRQ> getAttributes() {
			return attributes;
		}

		String getStepPrefix() {
			if (hasBackground() && withBackground()) {
				return background.getKeyword().toUpperCase() + AbstractReporter.COLON_INFIX;
			} else {
				return "";
			}
		}

		Step getStep(TestStep testStep) {
			Step step = scenarioLocationMap.get(testStep.getStepLine());
			if (step != null) {
				return step;
			}
			throw new IllegalStateException(String.format("Trying to get step for unknown line in feature. Scenario: %s, line: %s",
					scenario.getName(),
					getLine()
			));
		}

		Maybe<String> getId() {
			return id;
		}

		void setId(Maybe<String> newId) {
			if (id != null) {
				throw new IllegalStateException("Attempting re-set scenario ID for unfinished scenario.");
			}
			id = newId;
		}

		void setTestCase(TestCase testCase) {
			this.testCase = testCase;
			scenarioDesignation = testCase.getScenarioDesignation();
		}

		void nextBackgroundStep() {
			backgroundSteps.poll();
		}

		boolean isScenarioOutline(ScenarioDefinition scenario) {
			if (scenario != null) {
				return scenario instanceof ScenarioOutline;
			}
			return false;
		}

		boolean withBackground() {
			return !backgroundSteps.isEmpty();
		}

		boolean hasBackground() {
			return hasBackground && background != null;
		}

		String getOutlineIteration() {
			return outlineIteration;
		}

		public Maybe<String> getCurrentStepId() {
			return currentStepId;
		}

		public void setCurrentStepId(Maybe<String> currentStepId) {
			this.currentStepId = currentStepId;
		}

		public Maybe<String> getHookStepId() {
			return hookStepId;
		}

		public void setHookStepId(Maybe<String> hookStepId) {
			this.hookStepId = hookStepId;
		}

		public Result.Type getHookStatus() {
			return hookStatus;
		}

		public void setHookStatus(Result.Type hookStatus) {
			this.hookStatus = hookStatus;
		}

	}
}
