/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.dmn.core.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;

import org.drools.core.definitions.InternalKnowledgePackage;
import org.drools.core.impl.KnowledgeBaseImpl;
import org.kie.api.internal.io.ResourceTypePackage;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieRuntime;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNPackage;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.api.core.ast.BusinessKnowledgeModelNode;
import org.kie.dmn.api.core.ast.DMNNode;
import org.kie.dmn.api.core.ast.DecisionNode;
import org.kie.dmn.api.core.ast.InputDataNode;
import org.kie.dmn.api.core.event.DMNRuntimeEventListener;
import org.kie.dmn.core.api.DMNFactory;
import org.kie.dmn.core.api.EvaluatorResult;
import org.kie.dmn.core.ast.BusinessKnowledgeModelNodeImpl;
import org.kie.dmn.core.ast.DMNBaseNode;
import org.kie.dmn.core.ast.DecisionNodeImpl;
import org.kie.dmn.core.ast.InputDataNodeImpl;
import org.kie.dmn.core.compiler.DMNOption;
import org.kie.dmn.core.compiler.DMNProfile;
import org.kie.dmn.core.compiler.RuntimeTypeCheckOption;
import org.kie.dmn.core.util.Msg;
import org.kie.dmn.core.util.MsgUtil;
import org.kie.dmn.feel.runtime.FEELFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.kie.dmn.api.core.DMNDecisionResult.DecisionEvaluationStatus.EVALUATING;
import static org.kie.dmn.api.core.DMNDecisionResult.DecisionEvaluationStatus.FAILED;
import static org.kie.dmn.api.core.DMNDecisionResult.DecisionEvaluationStatus.SKIPPED;

public class DMNRuntimeImpl
        implements DMNRuntime {
    private static final Logger logger = LoggerFactory.getLogger( DMNRuntimeImpl.class );

    private KieRuntime                         runtime;
    private DMNRuntimeEventManagerImpl         eventManager;

    private boolean overrideRuntimeTypeCheck = false;

    public DMNRuntimeImpl(KieRuntime runtime) {
        this.runtime = runtime;
        this.eventManager = new DMNRuntimeEventManagerImpl();
    }

    @Override
    public List<DMNModel> getModels() {
        List<DMNModel> models = new ArrayList<>(  );
        runtime.getKieBase().getKiePackages().forEach( kpkg -> {
            DMNPackage dmnPkg = (DMNPackage) ((InternalKnowledgePackage) kpkg).getResourceTypePackages().get( ResourceType.DMN );
            if( dmnPkg != null ) {
                dmnPkg.getAllModels().values().forEach( model -> models.add( model ) );
            }
        } );
        return models;
    }

    @Override
    public DMNModel getModel(String namespace, String modelName) {
        InternalKnowledgePackage kpkg = (InternalKnowledgePackage) runtime.getKieBase().getKiePackage( namespace );
        if( kpkg == null ) {
            return null;
        }
        Map<ResourceType, ResourceTypePackage> map = kpkg.getResourceTypePackages();
        DMNPackage dmnpkg = (DMNPackage) map.get( ResourceType.DMN );
        return dmnpkg != null ? dmnpkg.getModel( modelName ) : null;
    }
    
    @Override
    public DMNModel getModelById(String namespace, String modelId) {
        InternalKnowledgePackage kpkg = (InternalKnowledgePackage) runtime.getKieBase().getKiePackage( namespace );
        if( kpkg == null ) {
            return null;
        }
        Map<ResourceType, ResourceTypePackage> map = kpkg.getResourceTypePackages();
        DMNPackage dmnpkg = (DMNPackage) map.get( ResourceType.DMN );
        return dmnpkg != null ? dmnpkg.getModelById( modelId ) : null;
    }

    @Override
    public DMNResult evaluateAll(DMNModel model, DMNContext context) {
        boolean performRuntimeTypeCheck = performRuntimeTypeCheck(model);
        DMNResultImpl result = createResult( model, context );
        // the engine should evaluate all Decisions belonging to the "local" model namespace, not imported decision explicitly.
        Set<DecisionNode> decisions = model.getDecisions().stream().filter(d -> d.getModelNamespace().equals(model.getNamespace())).collect(Collectors.toSet());
        for( DecisionNode decision : decisions ) {
            evaluateDecision(context, result, decision, performRuntimeTypeCheck);
        }
        return result;
    }

    @Override
    public DMNResult evaluateDecisionByName(DMNModel model, String decisionName, DMNContext context) {
        return evaluateByName(model, context, decisionName);
    }

    @Override
    public DMNResult evaluateDecisionById(DMNModel model, String decisionId, DMNContext context) {
        return evaluateById(model, context, decisionId);
    }

    @Override
    public DMNResult evaluateByName( DMNModel model, DMNContext context, String... decisionNames ) {
        final DMNResultImpl result = createResult( model, context );
        for (String name : decisionNames) {
            evaluateByNameInternal( model, context, result, name );
        }
        return result;
    }

    private void evaluateByNameInternal( DMNModel model, DMNContext context, DMNResultImpl result, String name ) {
        boolean performRuntimeTypeCheck = performRuntimeTypeCheck(model);
        Optional<DecisionNode> decision = Optional.ofNullable(model.getDecisionByName(name)).filter(d -> d.getModelNamespace().equals(model.getNamespace()));
        if (decision.isPresent()) {
            evaluateDecision(context, result, decision.get(), performRuntimeTypeCheck);
        } else {
            MsgUtil.reportMessage( logger,
                                   DMNMessage.Severity.ERROR,
                                   null,
                                   result,
                                   null,
                                   null,
                                   Msg.DECISION_NOT_FOUND_FOR_NAME,
                                   name );
        }
    }

    @Override
    public DMNResult evaluateById( DMNModel model, DMNContext context, String... decisionIds ) {
        final DMNResultImpl result = createResult( model, context );
        for ( String id : decisionIds ) {
            evaluateByIdInternal( model, context, result, id );
        }
        return result;
    }

    private void evaluateByIdInternal( DMNModel model, DMNContext context, DMNResultImpl result, String id ) {
        boolean performRuntimeTypeCheck = performRuntimeTypeCheck(model);
        Optional<DecisionNode> decision = Optional.ofNullable(model.getDecisionById(id)).filter(d -> d.getModelNamespace().equals(model.getNamespace()));
        if (decision.isPresent()) {
            evaluateDecision(context, result, decision.get(), performRuntimeTypeCheck);
        } else {
            MsgUtil.reportMessage( logger,
                                   DMNMessage.Severity.ERROR,
                                   null,
                                   result,
                                   null,
                                   null,
                                   Msg.DECISION_NOT_FOUND_FOR_ID,
                                   id );
        }
    }

    @Override
    public void addListener(DMNRuntimeEventListener listener) {
        this.eventManager.addListener( listener );
    }

    @Override
    public void removeListener(DMNRuntimeEventListener listener) {
        this.eventManager.removeListener( listener );
    }

    @Override
    public Set<DMNRuntimeEventListener> getListeners() {
        return this.eventManager.getListeners();
    }

    private DMNResultImpl createResult(DMNModel model, DMNContext context) {
        DMNResultImpl result = new DMNResultImpl(model);
        result.setContext( context.clone() );

        for (DecisionNode decision : model.getDecisions().stream().filter(d -> d.getModelNamespace().equals(model.getNamespace())).collect(Collectors.toSet())) {
            result.addDecisionResult(new DMNDecisionResultImpl(decision.getId(), decision.getName()));
        }
        return result;
    }

    private void evaluateBKM(DMNContext context, DMNResultImpl result, BusinessKnowledgeModelNode b, boolean typeCheck) {
        BusinessKnowledgeModelNodeImpl bkm = (BusinessKnowledgeModelNodeImpl) b;
        if (isNodeValueDefined(result, bkm, bkm)) {
            // already resolved
            // TODO: do we need to check if the defined variable is a function as it should?
            return;
        }
        // TODO: do we need to check/resolve dependencies?
        if( bkm.getEvaluator() == null ) {
            MsgUtil.reportMessage( logger,
                                   DMNMessage.Severity.WARN,
                                   bkm.getSource(),
                                   result,
                                   null,
                                   null,
                                   Msg.MISSING_EXPRESSION_FOR_BKM,
                                   getIdentifier( bkm ) );
            return;
        }
        try {
            DMNRuntimeEventManagerUtils.fireBeforeEvaluateBKM( eventManager, bkm, result );
            for( DMNNode dep : bkm.getDependencies().values() ) {
                if (typeCheck && !checkDependencyValueIsValid(dep, result)) {
                    MsgUtil.reportMessage( logger,
                                           DMNMessage.Severity.ERROR,
                                           ((DMNBaseNode) dep).getSource(),
                                           result,
                                           null,
                                           null,
                                           Msg.ERROR_EVAL_NODE_DEP_WRONG_TYPE,
                                           getIdentifier( bkm ),
                                           getIdentifier( dep ),
                                           MsgUtil.clipString(result.getContext().get(dep.getName()).toString(), 50),
                                           ((DMNBaseNode) dep).getType()
                                           );
                    return;
                }
                if (!isNodeValueDefined(result, bkm, dep)) {
                    if( dep instanceof BusinessKnowledgeModelNode ) {
                        boolean walkingIntoScope = walkIntoImportScope(result, bkm, dep);
                        evaluateBKM(context, result, (BusinessKnowledgeModelNode) dep, typeCheck);
                        if (walkingIntoScope) {
                            result.getContext().popScope();
                        }
                    } else {
                        MsgUtil.reportMessage( logger,
                                               DMNMessage.Severity.ERROR,
                                               bkm.getSource(),
                                               result,
                                               null,
                                               null,
                                               Msg.REQ_DEP_NOT_FOUND_FOR_NODE,
                                               getIdentifier( dep ),
                                               getIdentifier( bkm )
                        );
                        return;
                    }
                }
            }

            EvaluatorResult er = bkm.getEvaluator().evaluate( this, result );
            if( er.getResultType() == EvaluatorResult.ResultType.SUCCESS ) {
                FEELFunction resultFn = (FEELFunction) er.getResult();
                result.getContext().set(bkm.getBusinessKnowledModel().getVariable().getName(), resultFn);
            }
        } catch( Throwable t ) {
            MsgUtil.reportMessage( logger,
                                   DMNMessage.Severity.ERROR,
                                   bkm.getSource(),
                                   result,
                                   t,
                                   null,
                                   Msg.ERROR_EVAL_BKM_NODE,
                                   getIdentifier( bkm ),
                                   t.getMessage() );
        } finally {
            DMNRuntimeEventManagerUtils.fireAfterEvaluateBKM( eventManager, bkm, result );
        }
    }

    private boolean isNodeValueDefined(DMNResultImpl result, DMNNode callerNode, DMNNode node) {
        if (node.getModelNamespace().equals(result.getContext().scopeNamespace().orElse(result.getModel().getNamespace()))) {
            return result.getContext().isDefined(node.getName());
        } else {
            Optional<String> importAlias = callerNode.getModelImportAliasFor(node.getModelNamespace(), node.getModelName());
            if (importAlias.isPresent()) {
                Object aliasContext = result.getContext().get(importAlias.get());
                if (aliasContext != null && (aliasContext instanceof Map<?, ?>)) {
                    Map<?, ?> map = (Map<?, ?>) aliasContext;
                    map.containsKey(node.getName());
                }
            }
            return false;
        }
    }

    private boolean walkIntoImportScope(DMNResultImpl result, DMNNode callerNode, DMNNode destinationNode) {
        if (!result.getContext().scopeNamespace().isPresent()) {
            if (destinationNode.getModelNamespace().equals(result.getModel().getNamespace())) {
                return false;
            } else {
                Optional<String> importAlias = callerNode.getModelImportAliasFor(destinationNode.getModelNamespace(), destinationNode.getModelName());
                if (importAlias.isPresent()) {
                    result.getContext().pushScope(importAlias.get(), destinationNode.getModelNamespace());
                    return true;
                } else {
                    MsgUtil.reportMessage(logger,
                                          DMNMessage.Severity.ERROR,
                                          ((DMNBaseNode) callerNode).getSource(),
                                          result,
                                          null,
                                          null,
                                          Msg.IMPORT_NOT_FOUND_FOR_NODE_MISSING_ALIAS,
                                          new QName(destinationNode.getModelNamespace(), destinationNode.getModelName()),
                                          callerNode.getName()
                                          );
                    return false;
                }
            }
        } else {
            if (destinationNode.getModelNamespace().equals(result.getContext().scopeNamespace().get())) {
                return false;
            } else {
                Optional<String> importAlias = callerNode.getModelImportAliasFor(destinationNode.getModelNamespace(), destinationNode.getModelName());
                if (importAlias.isPresent()) {
                    result.getContext().pushScope(importAlias.get(), destinationNode.getModelNamespace());
                    return true;
                } else {
                    MsgUtil.reportMessage(logger,
                                          DMNMessage.Severity.ERROR,
                                          ((DMNBaseNode) callerNode).getSource(),
                                          result,
                                          null,
                                          null,
                                          Msg.IMPORT_NOT_FOUND_FOR_NODE_MISSING_ALIAS,
                                          new QName(destinationNode.getModelNamespace(), destinationNode.getModelName()),
                                          callerNode.getName());
                    return false;
                }
            }
        }

    }

    private boolean evaluateDecision(DMNContext context, DMNResultImpl result, DecisionNode d, boolean typeCheck) {
        DecisionNodeImpl decision = (DecisionNodeImpl) d;
        String decisionId = d.getModelNamespace().equals(result.getModel().getNamespace()) ? decision.getId() : decision.getModelNamespace() + "#" + decision.getId();
        if (isNodeValueDefined(result, decision, decision)) {
            // already resolved
            return true;
        } else {
            // check if the decision was already evaluated before and returned error
            DMNDecisionResult.DecisionEvaluationStatus status = Optional.ofNullable(result.getDecisionResultById(decisionId))
                                                                        .map(DMNDecisionResult::getEvaluationStatus)
                                                                        .orElse(DMNDecisionResult.DecisionEvaluationStatus.NOT_EVALUATED); // it might be an imported Decision.
            if ( FAILED == status || SKIPPED == status || EVALUATING == status ) {
                return false;
            }
        }
        try {
            DMNRuntimeEventManagerUtils.fireBeforeEvaluateDecision( eventManager, decision, result );
            boolean missingInput = false;
            DMNDecisionResultImpl dr = (DMNDecisionResultImpl) result.getDecisionResultById(decisionId);
            if (dr == null) { // an imported Decision now evaluated, requires the creation of the decision result:
                dr = new DMNDecisionResultImpl(decisionId, decision.getName());
                result.addDecisionResult(dr);
            }
            dr.setEvaluationStatus(DMNDecisionResult.DecisionEvaluationStatus.EVALUATING);
            for( DMNNode dep : decision.getDependencies().values() ) {
                try {
                    if (typeCheck && !checkDependencyValueIsValid(dep, result)) {
                        missingInput = true;
                        DMNMessage message = MsgUtil.reportMessage( logger,
                                DMNMessage.Severity.ERROR,
                                ((DMNBaseNode) dep).getSource(),
                                result,
                                null,
                                null,
                                Msg.ERROR_EVAL_NODE_DEP_WRONG_TYPE,
                                getIdentifier( decision ),
                                getIdentifier( dep ),
                                MsgUtil.clipString(result.getContext().get(dep.getName()).toString(), 50),
                                ((DMNBaseNode) dep).getType()
                                );
                        reportFailure( dr, message, DMNDecisionResult.DecisionEvaluationStatus.SKIPPED );
                    }
                } catch ( Exception e ) {
                    MsgUtil.reportMessage( logger,
                                           DMNMessage.Severity.ERROR,
                                           ((DMNBaseNode)dep).getSource(),
                                           result,
                                           e,
                                           null,
                                           Msg.ERROR_CHECKING_ALLOWED_VALUES,
                                           getIdentifier( dep ),
                                           e.getMessage() );
                }
                if (!isNodeValueDefined(result, decision, dep)) {
                    boolean walkingIntoScope = walkIntoImportScope(result, decision, dep);
                    if( dep instanceof DecisionNode ) {
                        if (!evaluateDecision(context, result, (DecisionNode) dep, typeCheck)) {
                            missingInput = true;
                            DMNMessage message = MsgUtil.reportMessage( logger,
                                                                        DMNMessage.Severity.ERROR,
                                                                        decision.getSource(),
                                                                        result,
                                                                        null,
                                                                        null,
                                                                        Msg.UNABLE_TO_EVALUATE_DECISION_REQ_DEP,
                                                                        getIdentifier( decision ),
                                                                        getIdentifier( dep ) );
                            reportFailure( dr, message, DMNDecisionResult.DecisionEvaluationStatus.SKIPPED );
                        }
                    } else if( dep instanceof BusinessKnowledgeModelNode ) {
                        evaluateBKM(context, result, (BusinessKnowledgeModelNode) dep, typeCheck);
                    } else {
                        missingInput = true;
                        DMNMessage message = MsgUtil.reportMessage( logger,
                                                                    DMNMessage.Severity.ERROR,
                                                                    decision.getSource(),
                                                                    result,
                                                                    null,
                                                                    null,
                                                                    Msg.REQ_DEP_NOT_FOUND_FOR_NODE,
                                                                    getIdentifier( dep ),
                                                                    getIdentifier( decision )
                                                                    );
                        reportFailure( dr, message, DMNDecisionResult.DecisionEvaluationStatus.SKIPPED );
                    }
                    if (walkingIntoScope) {
                        result.getContext().popScope();
                    }
                }
            }
            if( missingInput ) {
                return false;
            }
            if( decision.getEvaluator() == null ) {
                DMNMessage message = MsgUtil.reportMessage( logger,
                                                            DMNMessage.Severity.WARN,
                                                            decision.getSource(),
                                                            result,
                                                            null,
                                                            null,
                                                            Msg.MISSING_EXPRESSION_FOR_DECISION,
                                                            getIdentifier( decision ) );

                reportFailure( dr, message, DMNDecisionResult.DecisionEvaluationStatus.SKIPPED );
                return false;
            }
            try {
                EvaluatorResult er = decision.getEvaluator().evaluate( this, result );
                if( er.getResultType() == EvaluatorResult.ResultType.SUCCESS ) {
                    Object value = er.getResult();
                    if( ! decision.getResultType().isCollection() && value instanceof Collection &&
                        ((Collection)value).size()==1 ) {
                        // spec defines that "a=[a]", i.e., singleton collections should be treated as the single element
                        // and vice-versa
                        value = ((Collection)value).toArray()[0];
                    }

                    try {
                        if (typeCheck && !d.getResultType().isAssignableValue(value)) {
                            DMNMessage message = MsgUtil.reportMessage( logger,
                                    DMNMessage.Severity.ERROR,
                                    decision.getSource(),
                                    result,
                                    null,
                                    null,
                                    Msg.ERROR_EVAL_NODE_RESULT_WRONG_TYPE,
                                    getIdentifier( decision ),
                                    decision.getResultType(),
                                    value);
                            reportFailure( dr, message, DMNDecisionResult.DecisionEvaluationStatus.FAILED );
                            return false;
                        }
                    } catch ( Exception e ) {
                        MsgUtil.reportMessage( logger,
                                               DMNMessage.Severity.ERROR,
                                               decision.getSource(),
                                               result,
                                               e,
                                               null,
                                               Msg.ERROR_CHECKING_ALLOWED_VALUES,
                                               getIdentifier( decision ),
                                               e.getMessage() );
                        return false;
                    }

                    result.getContext().set(decision.getDecision().getVariable().getName(), value);
                    dr.setResult( value );
                    dr.setEvaluationStatus( DMNDecisionResult.DecisionEvaluationStatus.SUCCEEDED );
                } else {
                    dr.setEvaluationStatus( DMNDecisionResult.DecisionEvaluationStatus.FAILED );
                    return false;
                }
            } catch( Throwable t ) {
                DMNMessage message = MsgUtil.reportMessage( logger,
                                                            DMNMessage.Severity.ERROR,
                                                            decision.getSource(),
                                                            result,
                                                            t,
                                                            null,
                                                            Msg.ERROR_EVAL_DECISION_NODE,
                                                            getIdentifier( decision ),
                                                            t.getMessage() );

                reportFailure( dr, message, DMNDecisionResult.DecisionEvaluationStatus.FAILED );
            }
            return true;
        } finally {
            DMNRuntimeEventManagerUtils.fireAfterEvaluateDecision( eventManager, decision, result );
        }
    }

    private boolean checkDependencyValueIsValid(DMNNode dep, DMNResultImpl result) {
        if (dep instanceof InputDataNode) {
            InputDataNodeImpl inputDataNode = (InputDataNodeImpl) dep;
            BaseDMNTypeImpl dmnType = (BaseDMNTypeImpl) inputDataNode.getType();
            return dmnType.isAssignableValue( result.getContext().get( dep.getName() ) );
        }
        // if the dependency is NOT an InputData, the type coherence was checked at evaluation result assignment.
        return true;
    }

    private String getIdentifier(DMNNode node) {
        return node.getName() != null ? node.getName() : node.getId();
    }

    public boolean performRuntimeTypeCheck(DMNModel model) {
        return overrideRuntimeTypeCheck || ((DMNModelImpl) model).isRuntimeTypeCheck();
    }

    public final <T extends DMNOption> void setOption(T option) {
        if (option instanceof RuntimeTypeCheckOption) {
            this.overrideRuntimeTypeCheck = ((RuntimeTypeCheckOption) option).isRuntimeTypeCheck();
        }
    }

    private void reportFailure(DMNDecisionResultImpl dr, DMNMessage message, DMNDecisionResult.DecisionEvaluationStatus status) {
        dr.getMessages().add( message );
        dr.setEvaluationStatus( status );
    }

    @Override
    public DMNContext newContext() {
        return DMNFactory.newContext();
    }

    @Override
    public DMNRuntime getRuntime() {
        return this;
    }

    public List<DMNProfile> getProfiles() {
        // need list to preserve ordering
        List<DMNProfile> profiles = new ArrayList<>();
        runtime.getKieBase().getKiePackages().forEach(kpkg -> {
            DMNPackageImpl dmnPkg = (DMNPackageImpl) ((InternalKnowledgePackage) kpkg).getResourceTypePackages().get(ResourceType.DMN);
            if (dmnPkg != null) {
                for (DMNProfile p : dmnPkg.getProfiles()) {
                    if (!profiles.contains(p)) {
                        profiles.add(p);
                    }
                }
            }
        });
        return profiles;
    }

    @Override
    public ClassLoader getRootClassLoader() {
        return ((KnowledgeBaseImpl) runtime.getKieBase()).getRootClassLoader();
    }
}
