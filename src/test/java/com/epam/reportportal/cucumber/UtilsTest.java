package com.epam.reportportal.cucumber;

import com.epam.ta.reportportal.ws.model.ParameterResource;
import cucumber.runtime.Argument;
import org.junit.Test;
import rp.com.google.common.collect.Lists;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author <a href="mailto:ihar_kahadouski@epam.com">Ihar Kahadouski</a>
 */
public class UtilsTest {

	@Test
	public void retrieveParamsFromTestWithoutParamsTest() {
		List<ParameterResource> parameters = Utils.getParameters(Collections.emptyList(), "Test without params");
		assertNotNull(parameters);
		assertTrue(parameters.isEmpty());
	}

	@Test
	public void retrieveParamsTest() {
		String parameterName = "parameter";
		String parameterValue = "value";
		List<ParameterResource> parameters = Utils.getParameters(Lists.newArrayList(new Argument(1, parameterValue)),
				String.format("Test with <%s>", parameterName)
		);
		assertNotNull(parameters);
		assertEquals(1, parameters.size());
		parameters.forEach(it -> {
			assertEquals(parameterName, it.getKey());
			assertEquals(parameterValue, it.getValue());
		});
	}
}