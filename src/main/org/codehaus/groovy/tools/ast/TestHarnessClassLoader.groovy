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
package org.codehaus.groovy.tools.ast

import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import java.security.CodeSource
import org.codehaus.groovy.control.CompilationUnit.PrimaryClassNodeOperation
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.ast.ClassNode

/**
* ClassLoader exists so that TestHarnessOperation can be wired into the compile. 
*
* @author Hamlet D'Arcy
*/
class TestHarnessClassLoader extends GroovyClassLoader {

    private ASTTransformation transform
    private CompilePhase phase

    TestHarnessClassLoader(ASTTransformation transform, CompilePhase phase) {
        this.transform = transform
        this.phase = phase
    }

    protected CompilationUnit createCompilationUnit(CompilerConfiguration config, CodeSource codeSource) {

        CompilationUnit cu = super.createCompilationUnit(config, codeSource)
        cu.addPhaseOperation(new TestHarnessOperation(transform), phase.getPhaseNumber())
        return cu
    }
}