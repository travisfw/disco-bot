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
package org.codehaus.groovy.transform

/**
 * @author Paul King
 */
class InheritConstructorsTransformTest extends GroovyShellTestCase {

    void testStandardCase() {
        assertScript """
            @InheritConstructors class CustomException extends RuntimeException { }
            def ce = new CustomException('foo')
            assert ce.message == 'foo'
        """
    }

    void testOverrideCase() {
        assertScript """
            @InheritConstructors
            class CustomException2 extends RuntimeException {
                CustomException2() { super('bar') }
            }
            def ce = new CustomException2()
            assert ce.message == 'bar'
            ce = new CustomException2('foo')
            assert ce.message == 'foo'
        """
    }

}