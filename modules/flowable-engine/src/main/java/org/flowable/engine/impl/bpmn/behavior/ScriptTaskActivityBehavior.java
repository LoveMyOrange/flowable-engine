/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.engine.impl.bpmn.behavior;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.IOParameter;
import org.flowable.bpmn.model.ScriptTask;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.variable.VariableContainer;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.common.engine.impl.scripting.ScriptEngineRequest;
import org.flowable.common.engine.impl.scripting.ScriptingEngines;
import org.flowable.common.engine.impl.variable.MapDelegateVariableContainer;
import org.flowable.engine.DynamicBpmnConstants;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.helper.ErrorPropagation;
import org.flowable.engine.impl.bpmn.helper.SkipExpressionUtil;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.context.BpmnOverrideContext;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.IOParameterUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Implementation of the BPMN 2.0 script task.
 *
 * @author Joram Barrez
 * @author Christian Stettler
 * @author Falko Menge
 */
public class ScriptTaskActivityBehavior extends TaskActivityBehavior {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(ScriptTaskActivityBehavior.class);

    protected String scriptTaskId;
    protected String script;
    protected String language;
    protected String resultVariable;
    protected String skipExpression;
    protected boolean storeScriptVariables; // see https://activiti.atlassian.net/browse/ACT-1626

    public ScriptTaskActivityBehavior(String script, String language, String resultVariable) {
        this.script = script;
        this.language = language;
        this.resultVariable = resultVariable;
    }

    public ScriptTaskActivityBehavior(String scriptTaskId, String script, String language, String resultVariable, String skipExpression,
            boolean storeScriptVariables) {
        this(script, language, resultVariable);
        this.scriptTaskId = scriptTaskId;
        this.skipExpression = skipExpression;
        this.storeScriptVariables = storeScriptVariables;
    }

    @Override
    public void execute(DelegateExecution execution) {
        CommandContext commandContext = CommandContextUtil.getCommandContext();
        boolean isSkipExpressionEnabled = SkipExpressionUtil.isSkipExpressionEnabled(skipExpression, scriptTaskId, execution, commandContext);

        if (isSkipExpressionEnabled && SkipExpressionUtil.shouldSkipFlowElement(skipExpression, scriptTaskId, execution, commandContext)) {
            leave(execution);
            return;
        }

        if (CommandContextUtil.getProcessEngineConfiguration().isEnableProcessDefinitionInfoCache()) {
            ObjectNode taskElementProperties = BpmnOverrideContext.getBpmnOverrideElementProperties(scriptTaskId, execution.getProcessDefinitionId());
            if (taskElementProperties != null && taskElementProperties.has(DynamicBpmnConstants.SCRIPT_TASK_SCRIPT)) {
                String overrideScript = taskElementProperties.get(DynamicBpmnConstants.SCRIPT_TASK_SCRIPT).asText();
                if (StringUtils.isNotEmpty(overrideScript) && !overrideScript.equals(script)) {
                    script = overrideScript;
                }
            }
        }

        safelyExecuteScript(execution);
    }

    protected void safelyExecuteScript(DelegateExecution execution) {
        boolean noErrors = true;

        try {
            executeScript(execution);
        } catch (FlowableException e) {
            LOGGER.warn("Exception while executing {} : {}", execution, e.getMessage());

            noErrors = false;
            Throwable rootCause = ExceptionUtils.getRootCause(e);
            if (rootCause instanceof BpmnError) {
                ErrorPropagation.propagateError((BpmnError) rootCause, execution);
            } else if (rootCause instanceof FlowableException) {
                throw (FlowableException) rootCause;
            } else {
                throw e;
            }
        }
        if (noErrors) {
            leave(execution);
        }
    }

    protected void executeScript(DelegateExecution execution) {

        ProcessEngineConfigurationImpl processEngineConfiguration = CommandContextUtil.getProcessEngineConfiguration();
        ScriptingEngines scriptingEngines = processEngineConfiguration.getScriptingEngines();
        ScriptEngineRequest.Builder builder = ScriptEngineRequest.builder().script(script)
                .traceEnhancer(trace -> trace.addTraceTag("type", "scriptTask"))
                .language(language).scopeContainer(execution);
        builder = storeScriptVariables ? builder.storeScriptVariables() : builder;
        FlowElement flowElement = execution.getCurrentFlowElement();
        if (flowElement instanceof ScriptTask scriptTask) {
            List<IOParameter> inParameters = scriptTask.getInParameters();
            if (inParameters != null && !inParameters.isEmpty()) {
                MapDelegateVariableContainer inputVariableContainer = new MapDelegateVariableContainer();
                IOParameterUtil.processInParameters(inParameters, execution, inputVariableContainer, processEngineConfiguration.getExpressionManager());
                builder.inputVariableContainer(inputVariableContainer);
            } else if (scriptTask.isDoNotIncludeVariables()) {
                builder.inputVariableContainer(VariableContainer.empty());
            }
        }
        ScriptEngineRequest request = builder.build();
        Object result = scriptingEngines.evaluate(request).getResult();

        if (null != result) {
            if ("juel".equalsIgnoreCase(language) && (result instanceof String) && script.equals(result.toString())) {
                throw new FlowableException("Error evaluating juel script: \"" + script + "\" for " + execution);
            }
        }

        if (resultVariable != null) {
            execution.setVariable(resultVariable, result);
        }
    }
}
