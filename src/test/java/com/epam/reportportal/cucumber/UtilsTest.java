package com.epam.reportportal.cucumber;

import com.epam.ta.reportportal.ws.model.ParameterResource;
import cucumber.runtime.Argument;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;
import rp.com.google.common.collect.Lists;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * @author <a href="mailto:ihar_kahadouski@epam.com">Ihar Kahadouski</a>
 */
public class UtilsTest {

	@Test
	public void retrieveParamsFromTestWithoutParamsTest() {
		List<ParameterResource> parameters = Utils.getParameters(Collections.emptyList(), "Test without params");
		assertThat(parameters, allOf(notNullValue(), empty()));
	}

	@Test
	public void retrieveSingleParameterTest() {
		String parameterName = "parameter";
		String parameterValue = "value";
		List<ParameterResource> parameters = Utils.getParameters(Lists.newArrayList(new Argument(1, parameterValue)),
				String.format("Test with <%s>", parameterName)
		);
		assertThat(parameters, allOf(notNullValue(), hasSize(1)));
		parameters.forEach(it -> {
			assertThat(it.getKey(), equalTo(parameterName));
			assertThat(it.getValue(), equalTo(parameterValue));
		});
	}

	@Test
	public void retrieveParameters() {
		List<String> parameterNames = Lists.newArrayList("String", "int");
		List<String> parameterValues = Lists.newArrayList("val1", "val2");
		List<ParameterResource> parameters = Utils.getParameters(parameterValues.stream()
				.map(it -> new Argument(RandomUtils.nextInt(), it))
				.collect(Collectors.toList()), String.format("Test with %s", "<" + String.join("> <", parameterNames) + ">"));
		assertThat(parameters, allOf(notNullValue(), hasSize(2)));
		IntStream.range(0, parameters.size()).forEach(index -> {
			assertThat(parameters.get(index).getKey(), equalTo(parameterNames.get(index)));
			assertThat(parameters.get(index).getValue(), equalTo(parameterValues.get(index)));
		});
	}
}