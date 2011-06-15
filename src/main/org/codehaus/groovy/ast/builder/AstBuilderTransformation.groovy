/*
 * Copyright 2003-2010 the original author or authors.
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
package org.codehaus.groovy.ast.builder

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.ReaderSource
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException

/**
 * Transformation to capture ASTBuilder from code statements.
 * 
 * The AstBuilder "from code" approach is used with a single Closure
 * parameter. This transformation converts the ClosureExpression back
 * into source code and rewrites the AST so that the "from string" 
 * builder is invoked on the source. In order for this to work, the 
 * closure source must be given a goto label. It is the "from string" 
 * approach's responsibilty to remove the BlockStatement created
 * by the label. 
 *
 * @author Hamlet D'Arcy
 */

@GroovyASTTransformation (phase = CompilePhase.SEMANTIC_ANALYSIS)
public class AstBuilderTransformation implements ASTTransformation {

    public void visit(ASTNode[] nodes, SourceUnit sourceUnit) {

        // todo : are there other import types that can be specified?
        def transformer = new AstBuilderInvocationTrap(
                sourceUnit.getAST().imports,
                sourceUnit.getAST().importPackages,
                sourceUnit.source,
                sourceUnit
        )
        nodes?.each {ASTNode it ->
            if (!it instanceof AnnotationNode && !it instanceof ClassNode) {
                it.visit(transformer)
            }
        }
        sourceUnit.getAST()?.visit(transformer)
        sourceUnit.getAST()?.getStatementBlock()?.visit(transformer)
        sourceUnit.getAST()?.getClasses()?.each {ClassNode classNode ->

            classNode.methods.each {MethodNode node ->
                node?.code?.visit(transformer)
            }

            try {
                classNode.constructors.each {MethodNode node ->
                    node?.code?.visit(transformer)
                }
            } catch (MissingPropertyException ignored) {
                // todo: inner class nodes don't have a constructors field available
            }

            // all properties are also always fields
            classNode.fields.each {FieldNode node ->
                node?.initialValueExpression?.visit(transformer)
            }

            try {
                classNode.objectInitializers.each {Statement node ->
                    node?.visit(transformer)
                }
            } catch (MissingPropertyException ignored) {
                // todo: inner class nodes don't have a objectInitializers field available
            }

            // todo: is there anything to do with the module ???
        }
        sourceUnit.getAST()?.getMethods()?.each { MethodNode node ->
            node?.parameters?.defaultValue?.each {
                it?.visit(transformer)
            }
            node?.code?.visit(transformer)
        }
    }
}
