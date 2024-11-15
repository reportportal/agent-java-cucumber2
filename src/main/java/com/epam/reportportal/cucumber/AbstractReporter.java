/*
 * Copyright 2020 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.reportportal.cucumber;

import com.epam.reportportal.annotations.TestCaseId;
import com.epam.reportportal.annotations.attribute.Attributes;
import com.epam.reportportal.listeners.ItemStatus;
import com.epam.reportportal.listeners.ItemType;
import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.message.ReportPortalMessage;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.item.TestCaseIdEntry;
import com.epam.reportportal.service.tree.TestItemTree;
import com.epam.reportportal.utils.*;
import com.epam.reportportal.utils.files.ByteSource;
import com.epam.reportportal.utils.formatting.MarkdownUtils;
import com.epam.reportportal.utils.http.ContentType;
import com.epam.reportportal.utils.properties.SystemAttributesExtractor;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.ParameterResource;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import cucumber.api.HookType;
import cucumber.api.Result;
import cucumber.api.TestCase;
import cucumber.api.TestStep;
import cucumber.api.event.*;
import cucumber.api.formatter.Formatter;
import gherkin.ast.Feature;
import gherkin.ast.Step;
import gherkin.ast.Tag;
import gherkin.pickles.*;
import io.reactivex.Maybe;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.epam.reportportal.cucumber.Utils.*;
import static com.epam.reportportal.cucumber.util.ItemTreeUtils.createKey;
import static com.epam.reportportal.cucumber.util.ItemTreeUtils.retrieveLeaf;
import static java.lang.String.format;
import static com.epam.reportportal.utils.formatting.ExceptionUtils.getStackTrace;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Abstract Cucumber 2.x formatter for Report Portal
 *
 * @author Sergey Gvozdyukevich
 * @author Andrei Varabyeu
 * @author Serhii Zharskyi
 * @author Vadzim Hushchanskou
 */
public abstract class AbstractReporter implements Formatter {

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractReporter.class);
	private static final String AGENT_PROPERTIES_FILE = "agent.properties";
	private static final String STEP_DEFINITION_FIELD_NAME = "stepDefinition";
	private static final String GET_LOCATION_METHOD_NAME = "getLocation";
	private static final String METHOD_OPENING_BRACKET = "(";
	private static final String DOCSTRING_DECORATOR = "\n\"\"\"\n";
	private static final String ERROR_FORMAT = "Error:\n%s";
	private static final String DESCRIPTION_ERROR_FORMAT = "%s\n" + ERROR_FORMAT;

	public static final TestItemTree ITEM_TREE = new TestItemTree();
	private static volatile ReportPortal REPORT_PORTAL = ReportPortal.builder().build();

	protected Supplier<Launch> launch;
	protected static final String COLON_INFIX = ": ";
	protected static final String SKIPPED_ISSUE_KEY = "skippedIssue";

	private final Map<String, RunningContext.FeatureContext> currentFeatureContextMap = new ConcurrentHashMap<>();

	private final Map<Pair<Integer, String>, RunningContext.ScenarioContext> currentScenarioContextMap = new ConcurrentHashMap<>();

	private final ThreadLocal<RunningContext.ScenarioContext> currentScenarioContext = new ThreadLocal<>();

	// There is no event for recognizing end of feature in Cucumber.
	// This map is used to record the last scenario time and its feature uri.
	// End of feature occurs once launch is finished.
	private final Map<String, Date> featureEndTime = new ConcurrentHashMap<>();

	/**
	 * This map uses to record the description of the scenario and the step to append the error to the description.
	 */
	private final Map<String, String> descriptionsMap = new ConcurrentHashMap<>();
	/**
	 * This map uses to record errors to append to the description.
	 */
	private final Map<String, Throwable> errorMap = new ConcurrentHashMap<>();

	public static ReportPortal getReportPortal() {
		return REPORT_PORTAL;
	}

	protected static void setReportPortal(ReportPortal reportPortal) {
		REPORT_PORTAL = reportPortal;
	}

	protected void handleEndOfFeature() {
		currentFeatureContextMap.values().forEach(f -> {
			Date featureCompletionDateTime = featureEndTime.get(f.getUri());
			finishFeature(f.getFeatureId(), featureCompletionDateTime);
			removeFromTree(f);
		});
		currentFeatureContextMap.clear();
	}

	protected EventHandler<TestRunStarted> getTestRunStartedHandler() {
		return event -> beforeLaunch();
	}

	protected EventHandler<TestSourceRead> getTestSourceReadHandler() {
		return event -> RunningContext.FeatureContext.addTestSourceReadEvent(event.uri, event);
	}

	protected EventHandler<TestCaseStarted> getTestCaseStartedHandler() {
		return this::handleStartOfTestCase;
	}

	protected EventHandler<TestStepStarted> getTestStepStartedHandler() {
		return this::handleTestStepStarted;
	}

	protected EventHandler<TestStepFinished> getTestStepFinishedHandler() {
		return this::handleTestStepFinished;
	}

	protected EventHandler<TestCaseFinished> getTestCaseFinishedHandler() {
		return this::afterScenario;
	}

	protected EventHandler<TestRunFinished> getTestRunFinishedHandler() {
		return event -> {
			handleEndOfFeature();
			afterLaunch();
		};
	}

	protected EventHandler<EmbedEvent> getEmbedEventHandler() {
		return event -> embedding(event.mimeType, event.data);
	}

	protected EventHandler<WriteEvent> getWriteEventHandler() {
		return event -> sendLog(event.text);
	}

	/**
	 * Registers an event handler for a specific event.
	 * <p>
	 * The available events types are:
	 * <ul>
	 * <li>{@link TestRunStarted} - the first event sent.
	 * <li>{@link TestSourceRead} - sent for each feature file read, contains the feature file source.
	 * <li>{@link TestCaseStarted} - sent before starting the execution of a Test Case(/Pickle/Scenario), contains the Test Case
	 * <li>{@link TestStepStarted} - sent before starting the execution of a Test Step, contains the Test Step
	 * <li>{@link TestStepFinished} - sent after the execution of a Test Step, contains the Test Step and its Result.
	 * <li>{@link TestCaseFinished} - sent after the execution of a Test Case(/Pickle/Scenario), contains the Test Case and its Result.
	 * <li>{@link TestRunFinished} - the last event sent.
	 * <li>{@link EmbedEvent} - calling scenario.embed in a hook triggers this event.
	 * <li>{@link WriteEvent} - calling scenario.write in a hook triggers this event.
	 * </ul>
	 */
	@Override
	public void setEventPublisher(EventPublisher publisher) {
		publisher.registerHandlerFor(TestRunStarted.class, getTestRunStartedHandler());
		publisher.registerHandlerFor(TestSourceRead.class, getTestSourceReadHandler());
		publisher.registerHandlerFor(TestCaseStarted.class, getTestCaseStartedHandler());
		publisher.registerHandlerFor(TestStepStarted.class, getTestStepStartedHandler());
		publisher.registerHandlerFor(TestStepFinished.class, getTestStepFinishedHandler());
		publisher.registerHandlerFor(TestCaseFinished.class, getTestCaseFinishedHandler());
		publisher.registerHandlerFor(TestRunFinished.class, getTestRunFinishedHandler());
		publisher.registerHandlerFor(EmbedEvent.class, getEmbedEventHandler());
		publisher.registerHandlerFor(WriteEvent.class, getWriteEventHandler());
	}

	/**
	 * Start RP launch
	 */
	protected void startLaunch() {
		launch = new MemoizingSupplier<>(new Supplier<Launch>() {

			/* should not be lazy */
			private final Date startTime = Calendar.getInstance().getTime();

			@Override
			public Launch get() {
				final ReportPortal reportPortal = buildReportPortal();
				ListenerParameters parameters = reportPortal.getParameters();

				StartLaunchRQ rq = new StartLaunchRQ();
				rq.setName(parameters.getLaunchName());
				rq.setStartTime(startTime);
				rq.setMode(parameters.getLaunchRunningMode());
				HashSet<ItemAttributesRQ> attributes = new HashSet<>(parameters.getAttributes());
				rq.setAttributes(attributes);
				attributes.addAll(SystemAttributesExtractor.extract(AGENT_PROPERTIES_FILE, AbstractReporter.class.getClassLoader()));
				rq.setDescription(parameters.getDescription());
				rq.setRerun(parameters.isRerun());
				if (isNotBlank(parameters.getRerunOf())) {
					rq.setRerunOf(parameters.getRerunOf());
				}

				if (null != parameters.getSkippedAnIssue()) {
					ItemAttributesRQ skippedIssueAttribute = new ItemAttributesRQ();
					skippedIssueAttribute.setKey(SKIPPED_ISSUE_KEY);
					skippedIssueAttribute.setValue(parameters.getSkippedAnIssue().toString());
					skippedIssueAttribute.setSystem(true);
					attributes.add(skippedIssueAttribute);
				}

				return reportPortal.newLaunch(rq);
			}
		});
	}

	/**
	 * Manipulations before the launch starts
	 */
	protected void beforeLaunch() {
		startLaunch();
		Maybe<String> launchId = launch.get().start();
		ITEM_TREE.setLaunchId(launchId);
	}

	/**
	 * Extension point to customize ReportPortal instance
	 *
	 * @return ReportPortal
	 */
	protected ReportPortal buildReportPortal() {
		return ReportPortal.builder().build();
	}

	/**
	 * Finish RP launch
	 */
	protected void afterLaunch() {
		FinishExecutionRQ finishLaunchRq = new FinishExecutionRQ();
		finishLaunchRq.setEndTime(Calendar.getInstance().getTime());
		launch.get().finish(finishLaunchRq);
	}

	/**
	 * Extension point to customize scenario creation event/request
	 *
	 * @param testCase Cucumber's TestCase object
	 * @param name     the scenario name
	 * @param uri      the scenario feature file relative path
	 * @param line     the scenario text line number
	 * @return start test item request ready to send on RP
	 */
	@Nonnull
	protected StartTestItemRQ buildStartScenarioRequest(@Nonnull TestCase testCase, @Nonnull String name, @Nonnull String uri, int line) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(name);
		rq.setDescription(getDescription(testCase, uri));
		String codeRef = getCodeRef(uri, line);
		rq.setCodeRef(codeRef);
		rq.setAttributes(extractPickleTags(testCase.getTags()));
		rq.setStartTime(Calendar.getInstance().getTime());
		String type = getScenarioTestItemType();
		rq.setType(type);
		if ("STEP".equals(type)) {
			rq.setTestCaseId(ofNullable(getTestCaseId(codeRef, null)).map(TestCaseIdEntry::getId).orElse(null));
		}
		return rq;
	}

	/**
	 * Start Cucumber Scenario
	 *
	 * @param featureId       parent feature item id
	 * @param startScenarioRq scenario start request
	 * @return scenario item id
	 */
	@Nonnull
	protected Maybe<String> startScenario(@Nonnull Maybe<String> featureId, @Nonnull StartTestItemRQ startScenarioRq) {
		return launch.get().startTestItem(featureId, startScenarioRq);
	}

	private void addToTree(RunningContext.FeatureContext featureContext, RunningContext.ScenarioContext scenarioContext) {
		retrieveLeaf(featureContext.getUri(), ITEM_TREE).ifPresent(suiteLeaf -> suiteLeaf.getChildItems()
				.put(createKey(scenarioContext.getLine()), TestItemTree.createTestItemLeaf(scenarioContext.getId())));
	}

	protected RunningContext.ScenarioContext getCurrentScenarioContext() {
		return currentScenarioContext.get();
	}

	/**
	 * Start Cucumber scenario
	 *
	 * @param featureContext  current feature context
	 * @param scenarioContext current scenario context
	 */
	protected void beforeScenario(RunningContext.FeatureContext featureContext, RunningContext.ScenarioContext scenarioContext) {
		String scenarioName = Utils.buildName(
				scenarioContext.getKeyword(),
				AbstractReporter.COLON_INFIX,
				scenarioContext.getTestCase().getName()
		);
		StartTestItemRQ startTestItemRQ = buildStartScenarioRequest(scenarioContext.getTestCase(), scenarioName, featureContext.getUri(), scenarioContext.getLine());
		Maybe<String> id = startScenario(featureContext.getFeatureId(), startTestItemRQ);
		id.subscribe(scenarioId -> descriptionsMap.put(scenarioId, ofNullable(startTestItemRQ.getDescription()).orElse(StringUtils.EMPTY)));
		scenarioContext.setId(id);
		if (launch.get().getParameters().isCallbackReportingEnabled()) {
			addToTree(featureContext, scenarioContext);
		}
	}

	private void removeFromTree(RunningContext.FeatureContext featureContext, RunningContext.ScenarioContext scenarioContext) {
		retrieveLeaf(featureContext.getUri(), ITEM_TREE).ifPresent(suiteLeaf -> suiteLeaf.getChildItems()
				.remove(createKey(scenarioContext.getLine())));
	}

	/**
	 * Finish Cucumber scenario
	 * Put scenario end time in a map to check last scenario end time per feature
	 *
	 * @param event Cucumber's TestCaseFinished object
	 */
	protected void afterScenario(TestCaseFinished event) {
		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		if (mapItemStatus(event.result.getStatus()) == ItemStatus.FAILED) {
			Optional.ofNullable(event.result.getError())
					.ifPresent(error -> context.getId().subscribe(id -> errorMap.put(id, error)));
		}
		String featureUri = context.getFeatureUri();
		currentScenarioContextMap.remove(Pair.of(context.getLine(), featureUri));
		Date endTime = finishTestItem(context.getId(), event.result.getStatus());
		featureEndTime.put(featureUri, endTime);
		currentScenarioContext.set(null);
		removeFromTree(currentFeatureContextMap.get(context.getFeatureUri()), context);
	}

	/**
	 * Extension point to customize test creation event/request
	 *
	 * @param testStep   a cucumber step object
	 * @param stepPrefix a prefix of the step (e.g. 'Background')
	 * @param keyword    a step keyword (e.g. 'Given')
	 * @return Request to ReportPortal
	 */
	protected StartTestItemRQ buildStartStepRequest(TestStep testStep, String stepPrefix, String keyword) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(buildName(stepPrefix, keyword, testStep.getStepText()));
		rq.setDescription(buildMultilineArgument(testStep));
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType("STEP");
		String codeRef = getCodeRef(testStep);
		rq.setParameters(getParameters(codeRef, testStep));
		rq.setCodeRef(codeRef);
		rq.setTestCaseId(ofNullable(getTestCaseId(testStep, codeRef)).map(TestCaseIdEntry::getId).orElse(null));
		rq.setAttributes(getAttributes(testStep));
		return rq;
	}

	/**
	 * Start Cucumber Step
	 *
	 * @param scenarioId  parent scenario item id
	 * @param startStepRq step start request
	 * @return step item id
	 */
	@Nonnull
	protected Maybe<String> startStep(@Nonnull Maybe<String> scenarioId, @Nonnull StartTestItemRQ startStepRq) {
		return launch.get().startTestItem(scenarioId, startStepRq);
	}

	private void addToTree(RunningContext.ScenarioContext scenarioContext, String text, Maybe<String> stepId) {
		retrieveLeaf(
				scenarioContext.getFeatureUri(),
				scenarioContext.getLine(),
				ITEM_TREE
		).ifPresent(scenarioLeaf -> scenarioLeaf.getChildItems().put(createKey(text), TestItemTree.createTestItemLeaf(stepId)));
	}

	/**
	 * Start Cucumber step
	 *
	 * @param testStep a cucumber step object
	 */
	protected void beforeStep(TestStep testStep) {
		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		Step step = context.getStep(testStep);
		StartTestItemRQ startTestItemRQ = buildStartStepRequest(testStep, context.getStepPrefix(), step.getKeyword());
		Maybe<String> stepId = startStep(context.getId(), startTestItemRQ);
		context.setCurrentStepId(stepId);
		String stepText = step.getText();
		context.setCurrentText(stepText);
		if (startTestItemRQ.isHasStats()) {
			stepId.subscribe(id -> descriptionsMap.put(id, ofNullable(startTestItemRQ.getDescription()).orElse(StringUtils.EMPTY)));
		}
		if (launch.get().getParameters().isCallbackReportingEnabled()) {
			addToTree(context, stepText, stepId);
		}
	}

	/**
	 * Finish Cucumber step
	 *
	 * @param result Step result
	 */
	protected void afterStep(Result result) {
		reportResult(result, null);
		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		if (mapItemStatus(result.getStatus()) == ItemStatus.FAILED) {
			Optional.ofNullable(result.getError())
					.ifPresent(error -> context.getCurrentStepId().subscribe(id -> errorMap.put(id, error)));
		}
		finishTestItem(context.getCurrentStepId(), result.getStatus());
		context.setCurrentStepId(null);
		context.setCurrentText(null);
	}

	/**
	 * Extension point to customize test creation event/request
	 *
	 * @param hookType a cucumber hook type object
	 * @return Request to ReportPortal
	 */
	protected StartTestItemRQ buildStartHookRequest(HookType hookType) {
		StartTestItemRQ rq = new StartTestItemRQ();
		Pair<String, String> typeName = getHookTypeAndName(hookType);
		rq.setType(typeName.getKey());
		rq.setName(typeName.getValue());
		rq.setStartTime(Calendar.getInstance().getTime());
		return rq;
	}

	/**
	 * Start before/after-hook item on Report Portal
	 *
	 * @param parentId parent item id
	 * @param rq       hook start request
	 * @return hook item id
	 */
	@Nonnull
	protected Maybe<String> startHook(@Nonnull Maybe<String> parentId, @Nonnull StartTestItemRQ rq) {
		return launch.get().startTestItem(parentId, rq);
	}

	/**
	 * Called when before/after-hooks are started
	 *
	 * @param hookType a hook type
	 */
	protected void beforeHooks(HookType hookType) {
		StartTestItemRQ rq = buildStartHookRequest(hookType);

		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		context.setHookStepId(startHook(context.getId(), rq));
		context.setHookStatus(Result.Type.PASSED);
	}

	/**
	 * Called when before/after-hooks are finished
	 *
	 * @param hookType a hook type
	 */
	@SuppressWarnings("unused")
	protected void afterHooks(HookType hookType) {
		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		finishTestItem(context.getHookStepId(), context.getHookStatus());
		context.setHookStepId(null);
	}

	/**
	 * Called when a specific before/after-hook is finished
	 *
	 * @param step     TestStep object
	 * @param result   Hook result
	 * @param isBefore - if true, before-hook, if false - after-hook
	 */
	protected void hookFinished(TestStep step, Result result, Boolean isBefore) {
		reportResult(result, (isBefore ? "Before" : "After") + " hook: " + step.getCodeLocation());
		getCurrentScenarioContext().setHookStatus(result.getStatus());
	}

	/**
	 * Return RP launch test item name mapped to Cucumber feature
	 *
	 * @return test item name
	 */
	@Nonnull
	protected abstract String getFeatureTestItemType();

	/**
	 * Return RP launch test item name mapped to Cucumber scenario
	 *
	 * @return test item name
	 */
	@Nonnull
	protected abstract String getScenarioTestItemType();

	/**
	 * Report test item result and error (if present)
	 *
	 * @param result  - Cucumber result object
	 * @param message - optional message to be logged in addition
	 */
	protected void reportResult(Result result, String message) {
		Result.Type cukesStatus = result.getStatus();
		String level = mapLevel(cukesStatus);
		if (message != null) {
			sendLog(message, level);
		}
		String errorMessage = result.getErrorMessage();
		if (errorMessage != null) {
			sendLog(errorMessage, level);
		} else if (result.getError() != null) {
			sendLog(getStackTrace(result.getError(), new Throwable()), level);
		}
	}

	@Nullable
	private static String getDataType(@Nonnull byte[] data) {
		try {
			return MimeTypeDetector.detect(ByteSource.wrap(data), null);
		} catch (IOException e) {
			LOGGER.warn("Unable to detect MIME type", e);
		}
		return null;
	}

	/**
	 * Send a log with data attached.
	 *
	 * @param mimeType an attachment type
	 * @param data     data to attach
	 */
	protected void embedding(String mimeType, byte[] data) {
		String type = ofNullable(mimeType).filter(ContentType::isValidType).orElseGet(() -> getDataType(data));
		String attachmentName = ofNullable(type).map(t -> t.substring(0, t.indexOf("/"))).orElse("");
		ReportPortal.emitLog(
				new ReportPortalMessage(ByteSource.wrap(data), type, attachmentName),
				"UNKNOWN",
				Calendar.getInstance().getTime()
		);
	}

	/**
	 * Send a log entry to Report Portal with 'INFO' level.
	 *
	 * @param text a log text to send
	 * @deprecated use {@link #sendLog(String)}
	 */
	@Deprecated
	protected void write(String text) {
		sendLog(text, "INFO");
	}

	/**
	 * Send a text log entry to Report Portal with 'INFO' level, using current datetime as timestamp
	 *
	 * @param message a text message
	 */
	protected void sendLog(String message) {
		sendLog(message, "INFO");
	}

	/**
	 * Send a text log entry to Report Portal using current datetime as timestamp
	 *
	 * @param message a text message
	 * @param level   a log level, see standard Log4j / logback logging levels
	 */
	protected void sendLog(final String message, final String level) {
		ReportPortal.emitLog(message, level, Calendar.getInstance().getTime());
	}

	private boolean isBefore(TestStep step) {
		return HookType.Before == step.getHookType();
	}

	@Nonnull
	protected abstract Optional<Maybe<String>> getRootItemId();

	/**
	 * Extension point to customize feature creation event/request
	 *
	 * @param feature a Cucumber's Feature object
	 * @param uri     a path to the feature
	 * @return Request to ReportPortal
	 */
	@Nonnull
	protected StartTestItemRQ buildStartFeatureRequest(@Nonnull Feature feature, @Nonnull String uri) {
		String featureKeyword = feature.getKeyword();
		String featureName = feature.getName();
		StartTestItemRQ startFeatureRq = new StartTestItemRQ();
		startFeatureRq.setDescription(getDescription(feature, uri));
		startFeatureRq.setCodeRef(getCodeRef(uri, 0));
		startFeatureRq.setName(buildName(featureKeyword, AbstractReporter.COLON_INFIX, featureName));
		startFeatureRq.setAttributes(extractAttributes(feature.getTags()));
		startFeatureRq.setStartTime(Calendar.getInstance().getTime());
		startFeatureRq.setType(getFeatureTestItemType());
		return startFeatureRq;
	}

	/**
	 * Start Cucumber Feature
	 *
	 * @param startFeatureRq feature start request
	 * @return feature item id
	 */
	@Nonnull
	protected Maybe<String> startFeature(@Nonnull StartTestItemRQ startFeatureRq) {
		Optional<Maybe<String>> root = getRootItemId();
		return root.map(r -> launch.get().startTestItem(r, startFeatureRq)).orElseGet(() -> launch.get().startTestItem(startFeatureRq));
	}

	private void removeFromTree(RunningContext.FeatureContext featureContext) {
		ITEM_TREE.getTestItems().remove(createKey(featureContext.getUri()));
	}

	private void addToTree(RunningContext.FeatureContext context) {
		ITEM_TREE.getTestItems().put(createKey(context.getUri()), TestItemTree.createTestItemLeaf(context.getFeatureId()));
	}

	protected void handleStartOfTestCase(TestCaseStarted event) {
		TestCase testCase = event.testCase;
		RunningContext.FeatureContext newFeatureContext = new RunningContext.FeatureContext(testCase);
		String featureUri = newFeatureContext.getUri();
		RunningContext.FeatureContext featureContext = currentFeatureContextMap.computeIfAbsent(
				featureUri, u -> {
					getRootItemId(); // trigger root item creation
					newFeatureContext.setFeatureId(startFeature(buildStartFeatureRequest(newFeatureContext.getFeature(), featureUri)));
					if (launch.get().getParameters().isCallbackReportingEnabled()) {
						addToTree(newFeatureContext);
					}
					return newFeatureContext;
				}
		);

		if (!featureContext.getUri().equals(testCase.getUri())) {
			throw new IllegalStateException("Scenario URI does not match Feature URI.");
		}

		RunningContext.ScenarioContext newScenarioContext = featureContext.getScenarioContext(testCase);
		Pair<Integer, String> scenarioLineFeatureURI = Pair.of(newScenarioContext.getLine(), featureContext.getUri());
		RunningContext.ScenarioContext scenarioContext = currentScenarioContextMap.computeIfAbsent(
				scenarioLineFeatureURI, k -> {
					currentScenarioContext.set(newScenarioContext);
					return newScenarioContext;
				}
		);

		beforeScenario(featureContext, scenarioContext);
	}

	protected void handleTestStepStarted(TestStepStarted event) {
		TestStep testStep = event.testStep;
		if (testStep.isHook()) {
			beforeHooks(testStep.getHookType());
		} else {
			if (getCurrentScenarioContext().withBackground()) {
				getCurrentScenarioContext().nextBackgroundStep();
			}
			beforeStep(testStep);
		}
	}

	protected void handleTestStepFinished(TestStepFinished event) {
		if (event.testStep.isHook()) {
			hookFinished(event.testStep, event.result, isBefore(event.testStep));
			afterHooks(event.testStep.getHookType());
		} else {
			afterStep(event.result);
		}
	}

	/**
	 * Build finish test item request object
	 *
	 * @param itemId     item ID reference
	 * @param finishTime a datetime object to use as item end time
	 * @param status     item result status
	 * @return finish request
	 */
	@Nonnull
	@SuppressWarnings("unused")
	protected Maybe<FinishTestItemRQ> buildFinishTestItemRequest(@Nonnull Maybe<String> itemId, @Nullable Date finishTime,
																 @Nullable ItemStatus status) {
		return itemId.map(id -> {
			FinishTestItemRQ rq = new FinishTestItemRQ();
			if (status == ItemStatus.FAILED) {
				Optional<String> currentDescription = Optional.ofNullable(descriptionsMap.get(id));
				Optional<Throwable> errorDescription = Optional.ofNullable(errorMap.get(id));
				currentDescription.flatMap(description -> errorDescription
								.map(errorMessage -> resolveDescriptionErrorMessage(description, errorMessage)))
						.ifPresent(rq::setDescription);
			}
			ofNullable(status).ifPresent(s -> rq.setStatus(s.name()));
			rq.setEndTime(finishTime);
			return rq;
		});
	}

	/**
	 * Resolve description
	 * @param currentDescription Current description
	 * @param error Error message
	 * @return Description with error
	 */
	private String resolveDescriptionErrorMessage(String currentDescription, Throwable error) {
		return Optional.ofNullable(currentDescription)
				.filter(StringUtils::isNotBlank)
				.map(description -> format(DESCRIPTION_ERROR_FORMAT, currentDescription, error))
				.orElse(format(ERROR_FORMAT, error));
	}

	/**
	 * Finish a feature with specific date and time
	 *
	 * @param itemId   an ID of the item
	 * @param dateTime a date and time object to use as feature end time
	 */
	protected void finishFeature(Maybe<String> itemId, Date dateTime) {
		if (itemId == null) {
			LOGGER.error("BUG: Trying to finish unspecified test item.");
			return;
		}
		//noinspection ReactiveStreamsUnusedPublisher
		Date endTime = ofNullable(dateTime).orElse(Calendar.getInstance().getTime());
		Maybe<FinishTestItemRQ> finishTestItemRQMaybe = buildFinishTestItemRequest(itemId, endTime, null);
		finishTestItemRQMaybe.subscribe(finishTestItemRQ -> launch.get().finishTestItem(itemId, finishTestItemRQ));
	}

	/**
	 * Map Cucumber statuses to RP item statuses
	 *
	 * @param status - Cucumber status
	 * @return RP test item status and null if status is null
	 */
	@Nullable
	protected ItemStatus mapItemStatus(@Nullable Result.Type status) {
		if (status == null) {
			return null;
		} else {
			if (STATUS_MAPPING.get(status) == null) {
				LOGGER.error(String.format(
						"Unable to find direct mapping between Cucumber and ReportPortal for TestItem with status: '%s'.",
						status
				));
				return ItemStatus.SKIPPED;
			}
			return STATUS_MAPPING.get(status);
		}
	}

	/**
	 * Finish a test item with specified status
	 *
	 * @param itemId an ID of the item
	 * @param status the status of the item
	 * @return a date and time object of the finish event
	 */
	@Nullable
	protected Date finishTestItem(@Nullable Maybe<String> itemId, @Nullable Result.Type status) {
		if (itemId == null) {
			LOGGER.error("BUG: Trying to finish unspecified test item.");
			return null;
		}

		Date endTime = Calendar.getInstance().getTime();
		Maybe<FinishTestItemRQ> rqMaybe = buildFinishTestItemRequest(itemId, endTime, mapItemStatus(status));
		//noinspection ReactiveStreamsUnusedPublisher
		rqMaybe.subscribe(rq -> launch.get().finishTestItem(itemId, rq));
		return endTime;
	}

	/**
	 * Finish a test item with no specific status
	 *
	 * @param itemId an ID of the item
	 */
	protected void finishTestItem(Maybe<String> itemId) {
		finishTestItem(itemId, null);
	}

	/**
	 * Map Cucumber statuses to RP log levels
	 *
	 * @param cukesStatus - Cucumber status
	 * @return regular log level
	 */
	@Nonnull
	protected String mapLevel(@Nullable Result.Type cukesStatus) {
		if (cukesStatus == null) {
			return "ERROR";
		}
		String level = LOG_LEVEL_MAPPING.get(cukesStatus);
		return null == level ? "ERROR" : level;
	}

	/**
	 * Generate multiline argument (DataTable or DocString) representation
	 *
	 * @param step - Cucumber's TestStep object
	 * @return - transformed multiline argument (or empty string if there is none)
	 */
	@Nonnull
	protected String buildMultilineArgument(@Nonnull TestStep step) {
		List<List<String>> table = null;
		String docString = null;
		if (!step.getStepArgument().isEmpty()) {
			Argument argument = step.getStepArgument().get(0);
			if (argument instanceof PickleString) {
				docString = ((PickleString) argument).getContent();
			} else if (argument instanceof PickleTable) {
				table = ((PickleTable) argument).getRows()
						.stream()
						.map(r -> r.getCells().stream().map(PickleCell::getValue).collect(Collectors.toList()))
						.collect(Collectors.toList());
			}
		}

		StringBuilder marg = new StringBuilder();
		if (table != null) {
			marg.append(MarkdownUtils.formatDataTable(table));
		}

		if (docString != null) {
			marg.append(DOCSTRING_DECORATOR).append(docString).append(DOCSTRING_DECORATOR);
		}
		return marg.toString();
	}

	/**
	 * Transform tags from Cucumber to RP format
	 *
	 * @param tags - Cucumber tags
	 * @return set of attributes
	 */
	protected Set<ItemAttributesRQ> extractPickleTags(List<PickleTag> tags) {
		Set<ItemAttributesRQ> attributes = new HashSet<>();
		for (PickleTag tag : tags) {
			attributes.add(new ItemAttributesRQ(null, tag.getName()));
		}
		return attributes;
	}

	/**
	 * Transform tags from Cucumber to RP format
	 *
	 * @param tags - Cucumber tags
	 * @return set of attributes
	 */
	protected Set<ItemAttributesRQ> extractAttributes(List<Tag> tags) {
		Set<ItemAttributesRQ> attributes = new HashSet<>();
		for (Tag tag : tags) {
			attributes.add(new ItemAttributesRQ(null, tag.getName()));
		}
		return attributes;
	}

	/**
	 * Returns static attributes defined by {@link Attributes} annotation in code.
	 *
	 * @param testStep - Cucumber's TestStep object
	 * @return a set of attributes or null if no such method provided by the match object
	 */
	@Nullable
	protected Set<ItemAttributesRQ> getAttributes(@Nonnull TestStep testStep) {
		Object definitionMatch = getDefinitionMatch(testStep);
		if (definitionMatch != null) {
			try {
				Method method = retrieveMethod(definitionMatch);
				Attributes attributesAnnotation = method.getAnnotation(Attributes.class);
				if (attributesAnnotation != null) {
					return AttributeParser.retrieveAttributes(attributesAnnotation);
				}
			} catch (NoSuchFieldException | IllegalAccessException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Returns code reference for mapped code
	 *
	 * @param testStep Cucumber's TestStep object
	 * @return a code reference, or null if not possible to determine (ambiguous, undefined, etc.)
	 */
	@Nullable
	protected String getCodeRef(@Nonnull TestStep testStep) {
		return ofNullable(getDefinitionMatch(testStep)).flatMap(stepDefinitionMatch -> {
			try {
				Field stepDefinitionField = stepDefinitionMatch.getClass().getDeclaredField(STEP_DEFINITION_FIELD_NAME);
				stepDefinitionField.setAccessible(true);
				Object javaStepDefinition = stepDefinitionField.get(stepDefinitionMatch);
				Method getLocationMethod = javaStepDefinition.getClass().getDeclaredMethod(GET_LOCATION_METHOD_NAME, boolean.class);
				getLocationMethod.setAccessible(true);
				return of(String.valueOf(getLocationMethod.invoke(javaStepDefinition, true))).filter(r -> !r.isEmpty()).map(r -> {
					int openingBracketIndex = r.indexOf(METHOD_OPENING_BRACKET);
					if (openingBracketIndex > 0) {
						return r.substring(0, r.indexOf(METHOD_OPENING_BRACKET));
					} else {
						return r;
					}
				});
			} catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ignore) {
			}
			return Optional.empty();
		}).orElseGet(testStep::getCodeLocation);
	}

	/**
	 * Returns code reference for feature files by URI and text line number
	 *
	 * @param uri  a feature URI
	 * @param line a scenario line number
	 * @return a code reference
	 */
	@Nonnull
	protected String getCodeRef(@Nonnull String uri, int line) {
		return uri + ":" + line;
	}

	/**
	 * Return a Test Case ID for mapped code
	 *
	 * @param testStep Cucumber's TestStep object
	 * @param codeRef  a code reference
	 * @return Test Case ID entity or null if it's not possible to calculate
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	protected TestCaseIdEntry getTestCaseId(@Nonnull TestStep testStep, @Nullable String codeRef) {
		Object definitionMatch = getDefinitionMatch(testStep);
		if (definitionMatch != null) {
			try {
				Method method = retrieveMethod(definitionMatch);
				return TestCaseIdUtils.getTestCaseId(
						method.getAnnotation(TestCaseId.class),
						method,
						codeRef,
						(List<Object>) ARGUMENTS_TRANSFORM.apply(testStep.getDefinitionArgument())
				);
			} catch (NoSuchFieldException | IllegalAccessException ignore) {
			}
		}
		return getTestCaseId(codeRef, testStep.getDefinitionArgument());
	}

	/**
	 * Return a Test Case ID for a feature file
	 *
	 * @param codeRef   a code reference
	 * @param arguments a scenario arguments
	 * @return Test Case ID entity or null if it's not possible to calculate
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	protected TestCaseIdEntry getTestCaseId(@Nullable String codeRef, @Nullable List<cucumber.runtime.Argument> arguments) {
		return TestCaseIdUtils.getTestCaseId(codeRef, (List<Object>) ARGUMENTS_TRANSFORM.apply(arguments));
	}

	/**
	 * Returns a list of parameters for a step
	 *
	 * @param codeRef  a method code reference to retrieve parameter types
	 * @param testStep Cucumber's Step object
	 * @return a list of parameters or empty list if none
	 */
	@Nonnull
	protected List<ParameterResource> getParameters(@Nullable String codeRef, @Nonnull TestStep testStep) {
		List<Pair<String, String>> params = ofNullable(testStep.getDefinitionArgument()).map(a -> IntStream.range(0, a.size())
				.mapToObj(i -> Pair.of("arg" + i, a.get(i).getVal()))
				.collect(Collectors.toList())).orElse(new ArrayList<>());
		params.addAll(ofNullable(testStep.getStepArgument()).map(a -> IntStream.range(0, a.size()).mapToObj(i -> {
			Argument arg = a.get(i);
			String value;
			if (arg instanceof PickleString) {
				value = ((PickleString) arg).getContent();
			} else if (arg instanceof PickleTable) {
				value = MarkdownUtils.formatDataTable(((PickleTable) arg).getRows()
						.stream()
						.map(r -> r.getCells().stream().map(PickleCell::getValue).collect(Collectors.toList()))
						.collect(Collectors.toList()));
			} else {
				value = arg.toString();
			}
			return Pair.of("arg" + i, value);
		}).collect(Collectors.toList())).orElse(Collections.emptyList()));
		return ParameterUtils.getParameters(codeRef, params);
	}

	/**
	 * Build an item description for a scenario
	 *
	 * @param testCase a Cucumber's TestCase object
	 * @param uri      a feature URI
	 * @return item description
	 */
	@Nonnull
	@SuppressWarnings("unused")
	protected String getDescription(@Nonnull TestCase testCase, @Nonnull String uri) {
		return uri;
	}

	/**
	 * Build an item description for a feature
	 *
	 * @param feature a Cucumber's Feature object
	 * @param uri     a feature URI
	 * @return item description
	 */
	@Nonnull
	@SuppressWarnings("unused")
	protected String getDescription(@Nonnull Feature feature, @Nonnull String uri) {
		return uri;
	}

	/**
	 * Returns hook type and name as a <code>Pair</code>
	 *
	 * @param hookType Cucumber's hoo type
	 * @return a pair of type and name
	 */
	@Nonnull
	protected Pair<String, String> getHookTypeAndName(@Nonnull HookType hookType) {
		switch (hookType) {
			case Before:
				return Pair.of(ItemType.BEFORE_TEST.name(), "Before hooks");
			case After:
				return Pair.of(ItemType.AFTER_TEST.name(), "After hooks");
			default:
				return Pair.of(ItemType.TEST.name(), "Hook");
		}
	}
}
