/*
 * Copyright 2016 EPAM Systems
 *
 *
 * This file is part of EPAM Report Portal.
 * https://github.com/reportportal/agent-java-cucumber
 *
 * Report Portal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Report Portal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Report Portal.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.epam.reportportal.cucumber;

import com.epam.reportportal.annotations.TestCaseId;
import com.epam.reportportal.annotations.attribute.Attributes;
import com.epam.reportportal.listeners.ItemStatus;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.item.TestCaseIdEntry;
import com.epam.reportportal.utils.AttributeParser;
import com.epam.reportportal.utils.TestCaseIdUtils;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.ParameterResource;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ.File;
import cucumber.api.Result;
import cucumber.api.TestStep;
import cucumber.runtime.StepDefinitionMatch;
import gherkin.ast.Step;
import gherkin.ast.Tag;
import gherkin.pickles.*;
import io.reactivex.Maybe;
import io.reactivex.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rp.com.google.common.collect.ImmutableMap;
import rp.com.google.common.collect.Lists;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Optional.ofNullable;

public class Utils {
	private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);
	private static final String TABLE_SEPARATOR = "|";
	private static final String DOCSTRING_DECORATOR = "\n\"\"\"\n";
	private static final String DEFINITION_MATCH_FIELD_NAME = "definitionMatch";
	private static final String STEP_DEFINITION_FIELD_NAME = "stepDefinition";
	private static final String GET_LOCATION_METHOD_NAME = "getLocation";
	private static final String METHOD_OPENING_BRACKET = "(";
	private static final String METHOD_FIELD_NAME = "method";
	private static final String PARAMETER_REGEX = "<[^<>]+>";

	private Utils() {
	}

	//@formatter:off
	private static final Map<Result.Type, ItemStatus> STATUS_MAPPING = ImmutableMap.<Result.Type, ItemStatus>builder()
			.put(Result.Type.PASSED, ItemStatus.PASSED)
			.put(Result.Type.FAILED, ItemStatus.FAILED)
			.put(Result.Type.SKIPPED, ItemStatus.SKIPPED)
			.put(Result.Type.PENDING, ItemStatus.SKIPPED)
			.put(Result.Type.UNDEFINED, ItemStatus.SKIPPED)
			.put(Result.Type.AMBIGUOUS, ItemStatus.SKIPPED).build();
	//@formatter:on

	/**
	 * Map Cucumber statuses to RP item statuses
	 *
	 * @param status - Cucumber status
	 * @return RP test item status and null if status is null
	 */
	static String mapItemStatus(Result.Type status) {
		if (status == null) {
			return null;
		} else {
			if (STATUS_MAPPING.get(status) == null) {
				LOGGER.error(String.format("Unable to find direct mapping between Cucumber and ReportPortal for TestItem with status: '%s'.",
						status
				));
				return ItemStatus.SKIPPED.name();
			}
			return STATUS_MAPPING.get(status).name();
		}
	}

	static void finishFeature(Launch rp, Maybe<String> itemId, Date dateTime) {
		if (itemId == null) {
			LOGGER.error("BUG: Trying to finish unspecified test item.");
			return;
		}
		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setEndTime(dateTime);
		rp.finishTestItem(itemId, rq);
	}

	public static void finishTestItem(Launch rp, Maybe<String> itemId) {
		finishTestItem(rp, itemId, null);
	}

	public static Date finishTestItem(Launch rp, Maybe<String> itemId, Result.Type status) {
		if (itemId == null) {
			LOGGER.error("BUG: Trying to finish unspecified test item.");
			return null;
		}

		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setStatus(mapItemStatus(status));
		Date currentDate = Calendar.getInstance().getTime();
		rq.setEndTime(currentDate);
		rp.finishTestItem(itemId, rq);
		return currentDate;
	}

	public static Maybe<String> startNonLeafNode(Launch rp, Maybe<String> rootItemId, String name, String description, String codeRef,
			Set<ItemAttributesRQ> attributes, String type) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(name);
		rq.setDescription(description);
		rq.setCodeRef(codeRef);
		rq.setAttributes(attributes);
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType(type);
		if ("STEP".equals(type)) {
			rq.setTestCaseId(TestCaseIdUtils.getTestCaseId(codeRef, null).getId());
		}

		return rp.startTestItem(rootItemId, rq);
	}

	public static void sendLog(final String message, final String level, final File file) {
		ReportPortal.emitLog(item -> {
			SaveLogRQ rq = new SaveLogRQ();
			rq.setMessage(message);
			rq.setItemUuid(item);
			rq.setLevel(level);
			rq.setLogTime(Calendar.getInstance().getTime());
			if (file != null) {
				rq.setFile(file);
			}
			return rq;
		});
	}

	/**
	 * Transform tags from Cucumber to RP format
	 *
	 * @param tags - Cucumber tags
	 * @return set of attributes
	 */
	public static Set<ItemAttributesRQ> extractPickleTags(List<PickleTag> tags) {
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
	public static Set<ItemAttributesRQ> extractAttributes(List<Tag> tags) {
		Set<ItemAttributesRQ> attributes = new HashSet<>();
		for (Tag tag : tags) {
			attributes.add(new ItemAttributesRQ(null, tag.getName()));
		}
		return attributes;
	}

	/**
	 * Map Cucumber statuses to RP log levels
	 *
	 * @param cukesStatus - Cucumber status
	 * @return regular log level
	 */
	public static String mapLevel(String cukesStatus) {
		if (cukesStatus.equalsIgnoreCase("passed")) {
			return "INFO";
		} else if (cukesStatus.equalsIgnoreCase("skipped")) {
			return "WARN";
		} else {
			return "ERROR";
		}
	}

	/**
	 * Generate name representation
	 *
	 * @param prefix   - substring to be prepended at the beginning (optional)
	 * @param infix    - substring to be inserted between keyword and name
	 * @param argument - main text to process
	 * @param suffix   - substring to be appended at the end (optional)
	 * @return transformed string
	 */
	//TODO: pass Node as argument, not test event
	public static String buildNodeName(String prefix, String infix, String argument, String suffix) {
		return buildName(prefix, infix, argument, suffix);
	}

	private static String buildName(String prefix, String infix, String argument, String suffix) {
		return (prefix == null ? "" : prefix) + infix + argument + (suffix == null ? "" : suffix);
	}

	/**
	 * Generate multiline argument (DataTable or DocString) representation
	 *
	 * @param step - Cucumber step object
	 * @return - transformed multiline argument (or empty string if there is
	 * none)
	 */
	public static String buildMultilineArgument(TestStep step) {
		List<PickleRow> table = null;
		String dockString = "";
		StringBuilder marg = new StringBuilder();

		if (!step.getStepArgument().isEmpty()) {
			Argument argument = step.getStepArgument().get(0);
			if (argument instanceof PickleString) {
				dockString = ((PickleString) argument).getContent();
			} else if (argument instanceof PickleTable) {
				table = ((PickleTable) argument).getRows();
			}
		}
		if (table != null) {
			marg.append("\r\n");
			for (PickleRow row : table) {
				marg.append(TABLE_SEPARATOR);
				for (PickleCell cell : row.getCells()) {
					marg.append(" ").append(cell.getValue()).append(" ").append(TABLE_SEPARATOR);
				}
				marg.append("\r\n");
			}
		}

		if (!dockString.isEmpty()) {
			marg.append(DOCSTRING_DECORATOR).append(dockString).append(DOCSTRING_DECORATOR);
		}
		return marg.toString();
	}

	static String getStepName(TestStep step) {
		String stepName;
		if (step.isHook()) {
			stepName = "Hook: " + step.getHookType().toString();
		} else {
			stepName = step.getPickleStep().getText();
		}

		return stepName;
	}

	@Nullable
	public static Set<ItemAttributesRQ> getAttributes(TestStep testStep) {
		Field definitionMatchField = getDefinitionMatchField(testStep);
		if (definitionMatchField != null) {
			try {
				Method method = retrieveMethod(definitionMatchField, testStep);
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

	@Nullable
	public static String getCodeRef(TestStep testStep) {

		Field definitionMatchField = getDefinitionMatchField(testStep);

		if (definitionMatchField != null) {

			try {
				StepDefinitionMatch stepDefinitionMatch = (StepDefinitionMatch) definitionMatchField.get(testStep);
				Field stepDefinitionField = stepDefinitionMatch.getClass().getDeclaredField(STEP_DEFINITION_FIELD_NAME);
				stepDefinitionField.setAccessible(true);
				Object javaStepDefinition = stepDefinitionField.get(stepDefinitionMatch);
				Method getLocationMethod = javaStepDefinition.getClass().getDeclaredMethod(GET_LOCATION_METHOD_NAME, boolean.class);
				getLocationMethod.setAccessible(true);
				String fullCodeRef = String.valueOf(getLocationMethod.invoke(javaStepDefinition, true));
				return fullCodeRef != null ? fullCodeRef.substring(0, fullCodeRef.indexOf(METHOD_OPENING_BRACKET)) : null;
			} catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
				return null;
			}

		} else {
			return null;
		}
	}

	static List<ParameterResource> getParameters(List<cucumber.runtime.Argument> arguments, String text) {
		List<ParameterResource> parameters = Lists.newArrayList();
		List<String> parameterNames = Lists.newArrayList();
		Matcher matcher = Pattern.compile(PARAMETER_REGEX).matcher(text);
		while (matcher.find()) {
			parameterNames.add(text.substring(matcher.start() + 1, matcher.end() - 1));
		}
		IntStream.range(0, parameterNames.size()).forEach(index -> {
			String key = parameterNames.get(index);
			if (index < arguments.size()) {
				String val = arguments.get(index).getVal();
				ParameterResource parameterResource = new ParameterResource();
				parameterResource.setKey(key);
				parameterResource.setValue(val);
				parameters.add(parameterResource);
			}
		});
		return parameters;
	}

	private static Method retrieveMethod(Field definitionMatchField, TestStep testStep)
			throws NoSuchFieldException, IllegalAccessException {
		StepDefinitionMatch stepDefinitionMatch = (StepDefinitionMatch) definitionMatchField.get(testStep);
		Field stepDefinitionField = stepDefinitionMatch.getClass().getDeclaredField(STEP_DEFINITION_FIELD_NAME);
		stepDefinitionField.setAccessible(true);
		Object javaStepDefinition = stepDefinitionField.get(stepDefinitionMatch);
		Field methodField = javaStepDefinition.getClass().getDeclaredField(METHOD_FIELD_NAME);
		methodField.setAccessible(true);
		return (Method) methodField.get(javaStepDefinition);
	}

	private static final java.util.function.Function<List<cucumber.runtime.Argument>, List<?>> ARGUMENTS_TRANSFORM = arguments -> ofNullable(
			arguments).map(args -> args.stream().map(cucumber.runtime.Argument::getVal).collect(Collectors.toList())).orElse(null);

	@SuppressWarnings("unchecked")
	public static TestCaseIdEntry getTestCaseId(TestStep testStep, String codeRef) {
		Field definitionMatchField = getDefinitionMatchField(testStep);
		if (definitionMatchField != null) {
			try {
				Method method = retrieveMethod(definitionMatchField, testStep);
				return TestCaseIdUtils.getTestCaseId(method.getAnnotation(TestCaseId.class),
						method,
						codeRef,
						(List<Object>) ARGUMENTS_TRANSFORM.apply(testStep.getDefinitionArgument())
				);
			} catch (NoSuchFieldException | IllegalAccessException ignore) {
			}
		}
		return getTestCaseId(codeRef, testStep.getDefinitionArgument());
	}

	@SuppressWarnings("unchecked")
	private static TestCaseIdEntry getTestCaseId(String codeRef, List<cucumber.runtime.Argument> arguments) {
		return TestCaseIdUtils.getTestCaseId(codeRef, (List<Object>) ARGUMENTS_TRANSFORM.apply(arguments));
	}

	@Nullable
	private static Field getDefinitionMatchField(TestStep testStep) {

		Class<?> clazz = testStep.getClass();

		try {
			return clazz.getField(DEFINITION_MATCH_FIELD_NAME);
		} catch (NoSuchFieldException e) {
			do {
				try {
					Field definitionMatchField = clazz.getDeclaredField(DEFINITION_MATCH_FIELD_NAME);
					definitionMatchField.setAccessible(true);
					return definitionMatchField;
				} catch (NoSuchFieldException ignore) {
				}

				clazz = clazz.getSuperclass();
			} while (clazz != null);

			return null;
		}
	}

	@Nonnull
	public static String getDescription(@Nonnull String uri) {
		return uri;
	}

	@Nonnull
	public static String getCodeRef(@Nonnull String uri, int line) {
		return uri + ":" + line;
	}

	public static StartTestItemRQ buildStartStepRequest(String stepPrefix, TestStep testStep, Step step, boolean hasStats) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(Utils.buildNodeName(stepPrefix, step.getKeyword(), testStep.getStepText(), ""));
		rq.setDescription(Utils.buildMultilineArgument(testStep));
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType("STEP");
		rq.setHasStats(hasStats);
		rq.setParameters(Utils.getParameters(testStep.getDefinitionArgument(), step.getText()));
		String codeRef = Utils.getCodeRef(testStep);
		rq.setCodeRef(codeRef);
		rq.setTestCaseId(Utils.getTestCaseId(testStep, codeRef).getId());
		rq.setAttributes(Utils.getAttributes(testStep));
		return rq;
	}
}
