/*
 * Copyright 2008-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.groovy.transform;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.syntax.Token;
import org.objectweb.asm.Opcodes;

import java.lang.ref.SoftReference;
import java.util.Arrays;

/**
 * Handles generation of code for the @Lazy annotation
 *
 * @author Alex Tkachman
 * @author Paul King
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class LazyASTTransformation implements ASTTransformation, Opcodes {

    static final ClassNode SOFT_REF = ClassHelper.make(SoftReference.class);
    static final Expression NULL_EXPR = ConstantExpression.NULL;

    public void visit(ASTNode[] nodes, SourceUnit source) {
        if (nodes.length != 2 || !(nodes[0] instanceof AnnotationNode) || !(nodes[1] instanceof AnnotatedNode)) {
            throw new RuntimeException("Internal error: expecting [AnnotationNode, AnnotatedNode] but got: " + Arrays.asList(nodes));
        }

        AnnotatedNode parent = (AnnotatedNode) nodes[1];
        AnnotationNode node = (AnnotationNode) nodes[0];

        if (parent instanceof FieldNode) {
            FieldNode fieldNode = (FieldNode) parent;
            final Expression member = node.getMember("soft");
            final Expression init = getInitExpr(fieldNode);

            fieldNode.rename("$" + fieldNode.getName());
            fieldNode.setModifiers(ACC_PRIVATE | (fieldNode.getModifiers() & (~(ACC_PUBLIC | ACC_PROTECTED))));

            if (member instanceof ConstantExpression && ((ConstantExpression) member).getValue().equals(true))
                createSoft(fieldNode, init);
            else {
                create(fieldNode, init);
            }
        }
    }

    private void create(FieldNode fieldNode, final Expression initExpr) {
        BlockStatement body = new BlockStatement();
        final Expression fieldExpr = new FieldExpression(fieldNode);
        if ((fieldNode.getModifiers() & ACC_VOLATILE) == 0) {
            body.addStatement(new IfStatement(
                    new BooleanExpression(new BinaryExpression(fieldExpr, notEqualsOp(), NULL_EXPR)),
                    new ExpressionStatement(fieldExpr),
                    new ExpressionStatement(new BinaryExpression(fieldExpr, equalsOp(), initExpr))
            ));
        } else {
            body.addStatement(new IfStatement(
                    new BooleanExpression(new BinaryExpression(fieldExpr, notEqualsOp(), NULL_EXPR)),
                    new ReturnStatement(fieldExpr),
                    new SynchronizedStatement(
                            synchTarget(),
                            new IfStatement(
                                    new BooleanExpression(new BinaryExpression(fieldExpr, notEqualsOp(), NULL_EXPR)),
                                    new ReturnStatement(fieldExpr),
                                    new ReturnStatement(new BinaryExpression(fieldExpr, equalsOp(), initExpr))
                            )
                    )
            ));
        }
        addMethod(fieldNode, body, fieldNode.getType());
    }

    private void addMethod(FieldNode fieldNode, BlockStatement body, ClassNode type) {
        int visibility = ACC_PUBLIC;
        final String name = "get" + MetaClassHelper.capitalize(fieldNode.getName().substring(1));
        fieldNode.getDeclaringClass().addMethod(name, visibility, type, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, body);
    }

    private void createSoft(FieldNode fieldNode, Expression initExpr) {
        ClassNode type = fieldNode.getType();

        fieldNode.setType(SOFT_REF);

        createSoftGetter(fieldNode, initExpr, type);
        createSoftSetter(fieldNode, type);
    }

    private void createSoftGetter(FieldNode fieldNode, Expression initExpr, ClassNode type) {
        BlockStatement body = new BlockStatement();
        final Expression fieldExpr = new FieldExpression(fieldNode);

        final Expression resExpr = new VariableExpression("res", type);
        final MethodCallExpression callExpression = new MethodCallExpression(new FieldExpression(fieldNode), "get", new ArgumentListExpression());
        callExpression.setSafe(true);
        body.addStatement(new ExpressionStatement(new DeclarationExpression(resExpr, equalsOp(), callExpression)));

        BlockStatement elseBlock = new BlockStatement();
        elseBlock.addStatement(new ExpressionStatement(new BinaryExpression(resExpr, equalsOp(), initExpr)));
        elseBlock.addStatement(new ExpressionStatement(new BinaryExpression(fieldExpr, equalsOp(), new ConstructorCallExpression(SOFT_REF, resExpr))));
        elseBlock.addStatement(new ExpressionStatement(resExpr));

        final Statement mainIf = new IfStatement(
                new BooleanExpression(new BinaryExpression(resExpr, notEqualsOp(), NULL_EXPR)),
                new ExpressionStatement(resExpr),
                elseBlock
        );

        if ((fieldNode.getModifiers() & ACC_VOLATILE) == 0) {
            body.addStatement(mainIf);
        } else {
            body.addStatement(new IfStatement(
                    new BooleanExpression(new BinaryExpression(resExpr, notEqualsOp(), NULL_EXPR)),
                    new ExpressionStatement(resExpr),
                    new SynchronizedStatement(synchTarget(), mainIf)
            ));
        }
        addMethod(fieldNode, body, type);
    }

    private void createSoftSetter(FieldNode fieldNode, ClassNode type) {
        BlockStatement body = new BlockStatement();
        final Expression fieldExpr = new FieldExpression(fieldNode);
        final String name = "set" + MetaClassHelper.capitalize(fieldNode.getName().substring(1));
        final Parameter parameter = new Parameter(type, "value");
        final Expression paramExpr = new VariableExpression(parameter);
        body.addStatement(new IfStatement(
                new BooleanExpression(new BinaryExpression(paramExpr, notEqualsOp(), NULL_EXPR)),
                new ExpressionStatement(new BinaryExpression(fieldExpr, equalsOp(), new ConstructorCallExpression(SOFT_REF, paramExpr))),
                new ExpressionStatement(new BinaryExpression(fieldExpr, equalsOp(), NULL_EXPR))
        ));
        int visibility = ACC_PUBLIC;
        fieldNode.getDeclaringClass().addMethod(name, visibility, ClassHelper.VOID_TYPE, new Parameter[]{parameter}, ClassNode.EMPTY_ARRAY, body);
    }

    private Expression synchTarget() {
        return VariableExpression.THIS_EXPRESSION;
    }

    private static Token equalsOp() {
        return Token.newSymbol("=", -1, -1);
    }

    private static Token notEqualsOp() {
        return Token.newSymbol("!=", -1, -1);
    }

    private Expression getInitExpr(FieldNode fieldNode) {
        Expression initExpr = fieldNode.getInitialValueExpression();
        fieldNode.setInitialValueExpression(null);

        if (initExpr == null)
            initExpr = new ConstructorCallExpression(fieldNode.getType(), new ArgumentListExpression());

        return initExpr;
    }
}