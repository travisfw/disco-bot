/*
 * Copyright 2003-2011 the original author or authors.
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
package org.codehaus.groovy.classgen;

import groovy.util.GroovyTestCase;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.classgen.asm.BytecodeHelper;

/**
 * @author <a href="mailto:james@coredevelopers.net">James Strachan</a>
 * @version $Revision$
 */
public class BytecodeHelperTest extends GroovyTestCase {

    public void testTypeName() {
        assertEquals("[C", BytecodeHelper.getTypeDescription(ClassHelper.char_TYPE.makeArray()));
    }

    public void testMethodDescriptor() {
        String answer = BytecodeHelper.getMethodDescriptor(char[].class, new Class[0]);
        assertEquals("()[C", answer);

        answer = BytecodeHelper.getMethodDescriptor(int.class, new Class[]{long.class});
        assertEquals("(J)I", answer);

        answer = BytecodeHelper.getMethodDescriptor(String[].class, new Class[]{String.class, int.class});
        assertEquals("(Ljava/lang/String;I)[Ljava/lang/String;", answer);
    }
}
