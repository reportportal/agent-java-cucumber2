/*
 *  Copyright 2020 EPAM Systems
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.epam.reportportal.cucumber;

import com.epam.reportportal.listeners.ItemStatus;
import com.epam.reportportal.listeners.LogLevel;
import com.epam.reportportal.utils.reflect.Accessible;
import cucumber.api.Result;
import cucumber.api.TestStep;
import io.reactivex.annotations.Nullable;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

public class Utils {
	private static final String DEFINITION_MATCH_FIELD_NAME = "definitionMatch";
	private static final String STEP_DEFINITION_FIELD_NAME = "stepDefinition";
	private static final String METHOD_FIELD_NAME = "method";

	private Utils() {
		throw new RuntimeException("No instances should exist for the class!");
	}

	//@formatter:off
	public static final Map<Result.Type, ItemStatus> STATUS_MAPPING = Collections.unmodifiableMap(new HashMap<Result.Type, ItemStatus>(){{
			put(Result.Type.PASSED, ItemStatus.PASSED);
			put(Result.Type.FAILED, ItemStatus.FAILED);
			put(Result.Type.SKIPPED, ItemStatus.SKIPPED);
			put(Result.Type.PENDING, ItemStatus.SKIPPED);
			put(Result.Type.UNDEFINED, ItemStatus.SKIPPED);
			put(Result.Type.AMBIGUOUS, ItemStatus.SKIPPED);
	}});

	public static final Map<Result.Type, String> LOG_LEVEL_MAPPING = Collections.unmodifiableMap(new HashMap<Result.Type, String>(){{
			put(Result.Type.PASSED, LogLevel.INFO.name());
			put(Result.Type.FAILED, LogLevel.ERROR.name());
			put(Result.Type.SKIPPED, LogLevel.WARN.name());
			put(Result.Type.PENDING, LogLevel.WARN.name());
			put(Result.Type.UNDEFINED, LogLevel.WARN.name());
			put(Result.Type.AMBIGUOUS, LogLevel.WARN.name());
	}});
	//@formatter:on

	/**
	 * Generate name representation
	 *
	 * @param prefix   - substring to be prepended at the beginning (optional)
	 * @param infix    - substring to be inserted between keyword and name
	 * @param argument - main text to process
	 * @return transformed string
	 */
	public static String buildName(String prefix, String infix, String argument) {
		return (prefix == null ? "" : prefix) + infix + argument;
	}

	public static Method retrieveMethod(Object stepDefinitionMatch) throws IllegalAccessException, NoSuchFieldException {
		Object javaStepDefinition = Accessible.on(stepDefinitionMatch).field(STEP_DEFINITION_FIELD_NAME).getValue();
		Method method = null;
		if (javaStepDefinition != null) {
			method = (Method) Accessible.on(javaStepDefinition).field(METHOD_FIELD_NAME).getValue();
		}
		return method;
	}

	public static final java.util.function.Function<List<cucumber.runtime.Argument>, List<?>> ARGUMENTS_TRANSFORM = arguments -> ofNullable(
			arguments).map(args -> args.stream().map(cucumber.runtime.Argument::getVal).collect(Collectors.toList())).orElse(null);

	@Nullable
	public static Object getDefinitionMatch(@Nonnull TestStep testStep) {
		try {
			return Accessible.on(testStep).field(DEFINITION_MATCH_FIELD_NAME).getValue();
		} catch (NoSuchFieldException e) {
			return null;
		}
	}
}
