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

package com.epam.reportportal.cucumber.integration.hooks;

import cucumber.api.java.After;
import cucumber.api.java.Before;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmptySteps {
	private static final Logger LOGGER = LoggerFactory.getLogger(EmptySteps.class);

	@Before
	public void my_before_hook() {
		LOGGER.info("Inside 'my_before_hook'");
	}

	@Given("I have empty step")
	public void i_have_empty_step() {
		LOGGER.info("Inside 'I have empty step'");
	}

	@Then("I have another empty step")
	public void i_have_another_empty_step() {
		LOGGER.info("Inside 'I have another empty step'");
	}

	@After
	public void my_after_hook() {
		LOGGER.info("Inside 'my_after_hook'");
	}
}