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

package com.epam.reportportal.cucumber.integration.feature;

import com.epam.reportportal.annotations.ParameterKey;
import com.epam.reportportal.cucumber.integration.util.TestUtils;
import com.epam.reportportal.util.test.CommonUtils;
import cucumber.api.DataTable;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReportsTestWithParameters {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReportsTestWithParameters.class);

	@Given("It is test with parameters")
	public void infoLevel() throws InterruptedException {
		LOGGER.info("It is test with parameters");
		Thread.sleep(CommonUtils.MINIMAL_TEST_PAUSE);
	}

	@When("I have parameter (\\w+)")
	public void iHaveParameterStr(String str) throws InterruptedException {
		LOGGER.info("String parameter {}", str);
		Thread.sleep(CommonUtils.MINIMAL_TEST_PAUSE);
	}

	@When("I have a docstring parameter:")
	public void iHaveParameterDocstring(String str) throws InterruptedException {
		iHaveParameterStr(str);
	}

	@Then("I emit number (\\d+) on level info")
	public void infoLevel(int parameters) throws InterruptedException {
		LOGGER.info("Test with parameters: " + parameters);
		Thread.sleep(CommonUtils.MINIMAL_TEST_PAUSE);
	}

	@Given("It is a step with an integer parameter (\\d+)")
	public void iHaveAnIntInlineParameter(int parameter) throws InterruptedException {
		LOGGER.info("Integer parameter: " + parameter);
		Thread.sleep(CommonUtils.MINIMAL_TEST_PAUSE);
	}

	@When("I have a step with a string parameter (\\w+)")
	public void iHaveAnStrInlineParameter(String str) throws InterruptedException {
		LOGGER.info("String parameter {}", str);
		Thread.sleep(CommonUtils.MINIMAL_TEST_PAUSE);
	}

	@When("I have a step with a named string parameter (\\w+)")
	public void iHaveANamedStrInlineParameter(@ParameterKey("my name") String str) throws InterruptedException {
		LOGGER.info("String parameter {}", str);
		Thread.sleep(CommonUtils.MINIMAL_TEST_PAUSE);
	}

	@Given("a step with a data table:")
	public void testStep(DataTable dataTable) throws InterruptedException {
		LOGGER.info("DataTable parameter:\r\n{}", dataTable.toString());
		Thread.sleep(CommonUtils.MINIMAL_TEST_PAUSE);
	}
}
