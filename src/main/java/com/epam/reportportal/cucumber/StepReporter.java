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

import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import cucumber.api.Result;
import cucumber.api.TestStep;
import gherkin.ast.Step;
import io.reactivex.Maybe;

import java.util.Calendar;

/**
 * Cucumber reporter for ReportPortal that reports individual steps as test
 * methods.
 * <p>
 * Mapping between Cucumber and ReportPortal is as follows:
 * <ul>
 * <li>feature - SUITE</li>
 * <li>scenario - TEST</li>
 * <li>step - STEP</li>
 * </ul>
 * Background steps are reported as part of corresponding scenarios. Outline
 * example rows are reported as individual scenarios with [ROW NUMBER] after the
 * name. Hooks are reported as BEFORE/AFTER_METHOD items (NOTE: all screenshots
 * created in hooks will be attached to these, and not to the actual failing
 * steps!)
 *
 * @author Sergey_Gvozdyukevich
 * @author Serhii Zharskyi
 */
public class StepReporter extends AbstractReporter {
	private static final String RP_STORY_TYPE = "STORY";
	private static final String RP_TEST_TYPE = "SCENARIO";

	public StepReporter() {
		super();
	}

	@Override
	protected Maybe<String> getRootItemId() {
		return null;
	}

	@Override
	protected void beforeStep(TestStep testStep) {
		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		Step step = context.getStep(testStep);
		StartTestItemRQ rq = Utils.buildStartStepRequest(context.getStepPrefix(), testStep, step, true);
		context.setCurrentStepId(launch.get().startTestItem(context.getId(), rq));
	}

	@Override
	protected void afterStep(Result result) {
		reportResult(result, null);
		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		Utils.finishTestItem(launch.get(), context.getCurrentStepId(), result.getStatus());
		context.setCurrentStepId(null);
	}

	@Override
	protected void beforeHooks(Boolean isBefore) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(isBefore ? "Before hooks" : "After hooks");
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType(isBefore ? "BEFORE_TEST" : "AFTER_TEST");

		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		context.setHookStepId(launch.get().startTestItem(context.getId(), rq));
		context.setHookStatus(Result.Type.PASSED);
	}

	@Override
	protected void afterHooks(Boolean isBefore) {
		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		Utils.finishTestItem(launch.get(), context.getHookStepId(), context.getHookStatus());
		context.setHookStepId(null);
	}

	@Override
	protected void hookFinished(TestStep step, Result result, Boolean isBefore) {
		reportResult(result, (isBefore ? "Before" : "After") + " hook: " + step.getCodeLocation());
		RunningContext.ScenarioContext context = getCurrentScenarioContext();
		context.setHookStatus(result.getStatus());
	}

	@Override
	protected String getFeatureTestItemType() {
		return RP_STORY_TYPE;
	}

	@Override
	protected String getScenarioTestItemType() {
		return RP_TEST_TYPE;
	}
}
