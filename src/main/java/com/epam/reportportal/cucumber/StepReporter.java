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

import com.epam.reportportal.listeners.Statuses;
import com.epam.reportportal.service.item.TestCaseIdEntry;
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

	protected Maybe<String> currentStepId;
	protected Maybe<String> hookStepId;
	protected String hookStatus;

	public StepReporter() {
		super();
		currentStepId = null;
		hookStepId = null;
		hookStatus = null;
	}

	private String getType(RunningContext.FeatureContext context, Step step) {
		if (context.getBackground() != null) {
			if (context.getBackground().getSteps() == null) {
				return "STEP";
			} else {
				return context.getBackground().getSteps().contains(step) ? "BEFORE_TEST" : "STEP";
			}
		} else {
			return "STEP";
		}
	}

	@Override
	protected Maybe<String> getRootItemId() {
		return null;
	}

	@Override
	protected void beforeStep(TestStep testStep) {
		Step step = currentScenarioContext.getStep(testStep);
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(Utils.buildNodeName(currentScenarioContext.getStepPrefix(), step.getKeyword(), testStep.getStepText(), " "));
		rq.setDescription(Utils.buildMultilineArgument(testStep));
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType(getType(currentFeatureContext, step));
		String codeRef = Utils.getCodeRef(testStep);
		rq.setCodeRef(codeRef);
		TestCaseIdEntry testCaseIdEntry = Utils.getTestCaseId(testStep, codeRef);
		if (testCaseIdEntry != null) {
			rq.setTestCaseId(testCaseIdEntry.getId());
			rq.setTestCaseHash(testCaseIdEntry.getHash());
		}
		rq.setAttributes(Utils.getAttributes(testStep));
		currentStepId = RP.get().startTestItem(currentScenarioContext.getId(), rq);
	}

	@Override
	protected void afterStep(Result result) {
		reportResult(result, null);
		Utils.finishTestItem(RP.get(), currentStepId, result.getStatus().toString().toUpperCase());
		currentStepId = null;
	}

	@Override
	protected void beforeHooks(Boolean isBefore) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(isBefore ? "Before hooks" : "After hooks");
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType(isBefore ? "BEFORE_TEST" : "AFTER_TEST");

		hookStepId = RP.get().startTestItem(currentScenarioContext.getId(), rq);
		hookStatus = Statuses.PASSED;
	}

	@Override
	protected void afterHooks(Boolean isBefore) {
		Utils.finishTestItem(RP.get(), hookStepId, hookStatus);
		hookStepId = null;
	}

	@Override
	protected void hookFinished(TestStep step, Result result, Boolean isBefore) {
		reportResult(result, (isBefore ? "Before" : "After") + " hook: " + step.getCodeLocation());
		hookStatus = result.getStatus().toString();
	}

	@Override
	protected String getFeatureTestItemType() {
		return "SUITE";
	}

	@Override
	protected String getScenarioTestItemType() {
		return "SCENARIO";
	}
}
