/*
 * Copyright 2008-2011 the original author or authors.
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

import groovy.lang.MetaClass;
import groovy.lang.MissingPropertyException;
import groovy.lang.ReadOnlyPropertyException;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.util.*;

import static org.codehaus.groovy.transform.AbstractASTTransformUtil.*;
import static org.codehaus.groovy.transform.EqualsAndHashCodeASTTransformation.createEquals;
import static org.codehaus.groovy.transform.EqualsAndHashCodeASTTransformation.createHashCode;
import static org.codehaus.groovy.transform.ToStringASTTransformation.createToString;

/**
 * Handles generation of code for the @Immutable annotation.
 *
 * @author Paul King
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class ImmutableASTTransformation extends AbstractASTTransformation {

    /*
      Currently leaving BigInteger and BigDecimal in list but see:
      http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6348370

      Also, Color is not final so while not normally used with child
      classes, it isn't strictly immutable. Use at your own risk.
     */
    private static List<String> immutableList = Arrays.asList(
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.String",
            "java.math.BigInteger",
            "java.math.BigDecimal",
            "java.awt.Color",
            "java.net.URI"
    );
    private static final Class MY_CLASS = groovy.transform.Immutable.class;
    static final ClassNode MY_TYPE = ClassHelper.make(MY_CLASS);
    static final String MY_TYPE_NAME = "@" + MY_TYPE.getNameWithoutPackage();
    private static final ClassNode DATE_TYPE = ClassHelper.make(Date.class);
    private static final ClassNode CLONEABLE_TYPE = ClassHelper.make(Cloneable.class);
    private static final ClassNode COLLECTION_TYPE = ClassHelper.make(Collection.class);
    private static final ClassNode READONLYEXCEPTION_TYPE = ClassHelper.make(ReadOnlyPropertyException.class);
    private static final ClassNode DGM_TYPE = ClassHelper.make(DefaultGroovyMethods.class);
    private static final ClassNode SELF_TYPE = ClassHelper.make(ImmutableASTTransformation.class);
    private static final ClassNode HASHMAP_TYPE = ClassHelper.make(HashMap.class);
    private static final ClassNode MAP_TYPE = ClassHelper.make(Map.class);

    public void visit(ASTNode[] nodes, SourceUnit source) {
        init(nodes, source);
        AnnotatedNode parent = (AnnotatedNode) nodes[1];
        AnnotationNode node = (AnnotationNode) nodes[0];
        // temporarily have weaker check which allows for old Deprecated Annotation
//        if (!MY_TYPE.equals(node.getClassNode())) return;
        if (!node.getClassNode().getName().endsWith(".Immutable")) return;
        List<PropertyNode> newProperties = new ArrayList<PropertyNode>();

        if (parent instanceof ClassNode) {
            ClassNode cNode = (ClassNode) parent;
            String cName = cNode.getName();
            checkNotInterface(cNode, MY_TYPE_NAME);
            makeClassFinal(cNode);

            final List<PropertyNode> pList = getInstanceProperties(cNode);
            for (PropertyNode pNode : pList) {
                adjustPropertyForImmutability(pNode, newProperties);
            }
            for (PropertyNode pNode : newProperties) {
                cNode.getProperties().remove(pNode);
                addProperty(cNode, pNode);
            }
            final List<FieldNode> fList = cNode.getFields();
            for (FieldNode fNode : fList) {
                ensureNotPublic(cName, fNode);
            }
            createConstructors(cNode);
            createHashCode(cNode, true, false, false, new ArrayList<String>());
            createEquals(cNode, false, false, false, new ArrayList<String>());
            createToString(cNode, false, false, new ArrayList<String>());
        }
    }

    private void makeClassFinal(ClassNode cNode) {
        if ((cNode.getModifiers() & ACC_FINAL) == 0) {
            cNode.setModifiers(cNode.getModifiers() | ACC_FINAL);
        }
    }

    private void createConstructors(ClassNode cNode) {
        // pretty toString will remember how the user declared the params and print accordingly
        final FieldNode constructorField = cNode.addField("$print$names", ACC_PRIVATE | ACC_SYNTHETIC, ClassHelper.boolean_TYPE, null);
        final Expression constructorStyle = new VariableExpression(constructorField);
        if (!validateConstructors(cNode)) return;

        List<PropertyNode> list = getInstanceProperties(cNode);
        boolean specialHashMapCase = list.size() == 1 && list.get(0).getField().getType().equals(HASHMAP_TYPE);
        if (specialHashMapCase) {
            createConstructorMapSpecial(cNode, constructorStyle, list);
        } else {
            createConstructorMap(cNode, constructorStyle, list);
            createConstructorOrdered(cNode, constructorStyle, list);
        }
    }

    private void createConstructorOrdered(ClassNode cNode, Expression constructorStyle, List<PropertyNode> list) {
        final MapExpression argMap = new MapExpression();
        final Parameter[] orderedParams = new Parameter[list.size()];
        int index = 0;
        for (PropertyNode pNode : list) {
            Parameter param = new Parameter(pNode.getField().getType(), pNode.getField().getName());
            orderedParams[index++] = param;
            argMap.addMapEntryExpression(new ConstantExpression(pNode.getName()), new VariableExpression(pNode.getName()));
        }
        final BlockStatement orderedBody = new BlockStatement();
        orderedBody.addStatement(new ExpressionStatement(
                new ConstructorCallExpression(ClassNode.THIS, new ArgumentListExpression(new CastExpression(HASHMAP_TYPE, argMap)))
        ));
        orderedBody.addStatement(assignStatement(constructorStyle, ConstantExpression.FALSE));
        cNode.addConstructor(new ConstructorNode(ACC_PUBLIC, orderedParams, ClassNode.EMPTY_ARRAY, orderedBody));
    }

    private Statement createGetterBodyDefault(FieldNode fNode) {
        final Expression fieldExpr = new VariableExpression(fNode);
        return new ExpressionStatement(fieldExpr);
    }

    private Expression cloneCollectionExpr(Expression fieldExpr) {
        return new StaticMethodCallExpression(DGM_TYPE, "asImmutable", fieldExpr);
    }

    private Expression cloneArrayOrCloneableExpr(Expression fieldExpr) {
        return new MethodCallExpression(fieldExpr, "clone", MethodCallExpression.NO_ARGUMENTS);
    }

    private void createConstructorMapSpecial(ClassNode cNode, Expression constructorStyle, List<PropertyNode> list) {
        final BlockStatement body = new BlockStatement();
        body.addStatement(createConstructorStatementMapSpecial(list.get(0).getField()));
        createConstructorMapCommon(cNode, constructorStyle, body);
    }

    private void createConstructorMap(ClassNode cNode, Expression constructorStyle, List<PropertyNode> list) {
        final BlockStatement body = new BlockStatement();
        for (PropertyNode pNode : list) {
            body.addStatement(createConstructorStatement(cNode, pNode));
        }
        // check for missing properties
        Expression checkArgs = new ArgumentListExpression(new VariableExpression("this"), new VariableExpression("args"));
        body.addStatement(new ExpressionStatement(new StaticMethodCallExpression(SELF_TYPE, "checkPropNames", checkArgs)));
        createConstructorMapCommon(cNode, constructorStyle, body);
    }

    private void createConstructorMapCommon(ClassNode cNode, Expression constructorStyle, BlockStatement body) {
        final List<FieldNode> fList = cNode.getFields();
        for (FieldNode fNode : fList) {
            if (fNode.isPublic()) continue; // public fields will be rejected elsewhere
            if (cNode.getProperty(fNode.getName()) != null) continue; // a property
            if (fNode.isFinal() && fNode.isStatic()) continue;
            if (fNode.getName().contains("$")) continue; // internal field
            if (fNode.isFinal() && fNode.getInitialExpression() != null) body.addStatement(checkFinalArgNotOverridden(cNode, fNode));
            body.addStatement(createConstructorStatementDefault(fNode));
        }
        body.addStatement(assignStatement(constructorStyle, ConstantExpression.TRUE));
        final Parameter[] params = new Parameter[]{new Parameter(HASHMAP_TYPE, "args")};
        cNode.addConstructor(new ConstructorNode(ACC_PUBLIC, params, ClassNode.EMPTY_ARRAY, new IfStatement(
                equalsNullExpr(new VariableExpression("args")),
                new EmptyStatement(),
                body)));
    }

    private Statement checkFinalArgNotOverridden(ClassNode cNode, FieldNode fNode) {
        final String name = fNode.getName();
        Expression value = findArg(name);
        return new IfStatement(
                equalsNullExpr(value),
                new EmptyStatement(),
                new ThrowStatement(new ConstructorCallExpression(READONLYEXCEPTION_TYPE,
                        new ArgumentListExpression(new ConstantExpression(name),
                                new ConstantExpression(cNode.getName())))));
    }

    private Statement createConstructorStatementMapSpecial(FieldNode fNode) {
        final Expression fieldExpr = new VariableExpression(fNode);
        Expression initExpr = fNode.getInitialValueExpression();
        if (initExpr == null) initExpr = ConstantExpression.NULL;
        Expression namedArgs = findArg(fNode.getName());
        Expression baseArgs = new VariableExpression("args");
        return new IfStatement(
                equalsNullExpr(baseArgs),
                new IfStatement(
                        equalsNullExpr(initExpr),
                        new EmptyStatement(),
                        assignStatement(fieldExpr, cloneCollectionExpr(initExpr))),
                new IfStatement(
                        equalsNullExpr(namedArgs),
                        new IfStatement(
                                isTrueExpr(new MethodCallExpression(baseArgs, "containsKey", new ConstantExpression(fNode.getName()))),
                                assignStatement(fieldExpr, namedArgs),
                                assignStatement(fieldExpr, cloneCollectionExpr(baseArgs))),
                        new IfStatement(
                                isOneExpr(new MethodCallExpression(baseArgs, "size", MethodCallExpression.NO_ARGUMENTS)),
                                assignStatement(fieldExpr, cloneCollectionExpr(namedArgs)),
                                assignStatement(fieldExpr, cloneCollectionExpr(baseArgs)))
                )
        );
    }

    private void ensureNotPublic(String cNode, FieldNode fNode) {
        String fName = fNode.getName();
        // TODO: do we need to lock down things like: $ownClass
        if (fNode.isPublic() && !fName.contains("$") && !(fNode.isStatic() && fNode.isFinal())) {
            addError("Public field '" + fName + "' not allowed for " + MY_TYPE_NAME + " class '" + cNode + "'.", fNode);
        }
    }

    private void addProperty(ClassNode cNode, PropertyNode pNode) {
        final FieldNode fn = pNode.getField();
        cNode.getFields().remove(fn);
        cNode.addProperty(pNode.getName(), pNode.getModifiers() | ACC_FINAL, pNode.getType(),
                pNode.getInitialExpression(), pNode.getGetterBlock(), pNode.getSetterBlock());
        final FieldNode newfn = cNode.getField(fn.getName());
        cNode.getFields().remove(newfn);
        cNode.addField(fn);
    }

    private boolean validateConstructors(ClassNode cNode) {
        if (cNode.getDeclaredConstructors().size() != 0) {
            // TODO: allow constructors which only call provided constructor?
            addError("Explicit constructors not allowed for " + ImmutableASTTransformation.MY_TYPE_NAME + " class: " + cNode.getNameWithoutPackage(), cNode.getDeclaredConstructors().get(0));
        }
        return true;
    }

    private Statement createConstructorStatement(ClassNode cNode, PropertyNode pNode) {
        FieldNode fNode = pNode.getField();
        final ClassNode fieldType = fNode.getType();
        Statement statement = null;
        if (fieldType.isArray() || fieldType.implementsInterface(CLONEABLE_TYPE)) {
            statement = createConstructorStatementArrayOrCloneable(fNode);
        } else if (fieldType.isDerivedFrom(DATE_TYPE)) {
            statement = createConstructorStatementDate(fNode);
        } else if (isOrImplements(fieldType, COLLECTION_TYPE) || fieldType.isDerivedFrom(COLLECTION_TYPE) || isOrImplements(fieldType, MAP_TYPE) || fieldType.isDerivedFrom(MAP_TYPE)) {
            statement = createConstructorStatementCollection(fNode);
        } else if (isKnownImmutable(fieldType)) {
            statement = createConstructorStatementDefault(fNode);
        } else if (fieldType.isResolved()) {
            addError(createErrorMessage(cNode.getName(), fNode.getName(), fieldType.getName(), "compiling"), fNode);
        } else {
            statement = createConstructorStatementGuarded(cNode, fNode);
        }
        return statement;
    }

    private Statement createConstructorStatementGuarded(ClassNode cNode, FieldNode fNode) {
        final Expression fieldExpr = new VariableExpression(fNode);
        Expression initExpr = fNode.getInitialValueExpression();
        if (initExpr == null) initExpr = ConstantExpression.NULL;
        Expression unknown = findArg(fNode.getName());
        return new IfStatement(
                equalsNullExpr(unknown),
                new IfStatement(
                        equalsNullExpr(initExpr),
                        new EmptyStatement(),
                        assignStatement(fieldExpr, checkUnresolved(cNode, fNode, initExpr))),
                assignStatement(fieldExpr, checkUnresolved(cNode, fNode, unknown)));
    }

    private Expression checkUnresolved(ClassNode cNode, FieldNode fNode, Expression value) {
        Expression args = new TupleExpression(new ConstantExpression(cNode.getName()), new ConstantExpression(fNode.getName()), value);
        return new StaticMethodCallExpression(SELF_TYPE, "checkImmutable", args);
    }

    private Statement createConstructorStatementCollection(FieldNode fNode) {
        final Expression fieldExpr = new VariableExpression(fNode);
        Expression initExpr = fNode.getInitialValueExpression();
        if (initExpr == null) initExpr = ConstantExpression.NULL;
        Expression collection = findArg(fNode.getName());
        return new IfStatement(
                equalsNullExpr(collection),
                new IfStatement(
                        equalsNullExpr(initExpr),
                        new EmptyStatement(),
                        assignStatement(fieldExpr, cloneCollectionExpr(initExpr))),
                new IfStatement(
                    isInstanceOf(collection, CLONEABLE_TYPE),
                    assignStatement(fieldExpr, cloneCollectionExpr(cloneArrayOrCloneableExpr(collection))),
                    assignStatement(fieldExpr, cloneCollectionExpr(collection))));
    }

    private boolean isKnownImmutable(ClassNode fieldType) {
        if (!fieldType.isResolved()) return false;
        return fieldType.isEnum() ||
                ClassHelper.isPrimitiveType(fieldType) ||
                fieldType.getAnnotations(MY_TYPE).size() != 0 ||
                inImmutableList(fieldType.getName());
    }

    private static boolean inImmutableList(String typeName) {
        return immutableList.contains(typeName);
    }

    private Statement createConstructorStatementArrayOrCloneable(FieldNode fNode) {
        final Expression fieldExpr = new VariableExpression(fNode);
        Expression initExpr = fNode.getInitialValueExpression();
        if (initExpr == null) initExpr = ConstantExpression.NULL;
        final Expression array = findArg(fNode.getName());
        return new IfStatement(
                equalsNullExpr(array),
                new IfStatement(
                        equalsNullExpr(initExpr),
                        assignStatement(fieldExpr, ConstantExpression.NULL),
                        assignStatement(fieldExpr, cloneArrayOrCloneableExpr(initExpr))),
                assignStatement(fieldExpr, cloneArrayOrCloneableExpr(array)));
    }

    private Statement createConstructorStatementDate(FieldNode fNode) {
        final Expression fieldExpr = new VariableExpression(fNode);
        Expression initExpr = fNode.getInitialValueExpression();
        if (initExpr == null) initExpr = ConstantExpression.NULL;
        final Expression date = findArg(fNode.getName());
        return new IfStatement(
                equalsNullExpr(date),
                new IfStatement(
                        equalsNullExpr(initExpr),
                        assignStatement(fieldExpr, ConstantExpression.NULL),
                        assignStatement(fieldExpr, cloneDateExpr(initExpr))),
                assignStatement(fieldExpr, cloneDateExpr(date)));
    }

    private Expression cloneDateExpr(Expression origDate) {
        return new ConstructorCallExpression(DATE_TYPE,
                new MethodCallExpression(origDate, "getTime", MethodCallExpression.NO_ARGUMENTS));
    }

    private void adjustPropertyForImmutability(PropertyNode pNode, List<PropertyNode> newNodes) {
        final FieldNode fNode = pNode.getField();
        fNode.setModifiers((pNode.getModifiers() & (~ACC_PUBLIC)) | ACC_FINAL | ACC_PRIVATE);
        adjustPropertyNode(pNode, createGetterBody(fNode));
        newNodes.add(pNode);
    }

    private void adjustPropertyNode(PropertyNode pNode, Statement getterBody) {
        pNode.setSetterBlock(null);
        pNode.setGetterBlock(getterBody);
    }

    private Statement createGetterBody(FieldNode fNode) {
        BlockStatement body = new BlockStatement();
        final ClassNode fieldType = fNode.getType();
        final Statement statement;
        if (fieldType.isArray() || fieldType.implementsInterface(CLONEABLE_TYPE)) {
            statement = createGetterBodyArrayOrCloneable(fNode);
        } else if (fieldType.isDerivedFrom(DATE_TYPE)) {
            statement = createGetterBodyDate(fNode);
        } else {
            statement = createGetterBodyDefault(fNode);
        }
        body.addStatement(statement);
        return body;
    }

    private static String createErrorMessage(String className, String fieldName, String typeName, String mode) {
        return MY_TYPE_NAME + " processor doesn't know how to handle field '" + fieldName + "' of type '" +
                prettyTypeName(typeName) + "' while " + mode + " class " + className + ".\n" +
                MY_TYPE_NAME + " classes only support properties with effectively immutable types including:\n" +
                "- Strings, primitive types, wrapper types, BigInteger and BigDecimal, enums\n" +
                "- other " + MY_TYPE_NAME + " classes and known immutables (java.awt.Color, java.net.URI)\n" +
                "- Cloneable classes, collections, maps and arrays, and other classes with special handling (java.util.Date)\n" +
                "Other restrictions apply, please see the groovydoc for " + MY_TYPE_NAME + " for further details";
    }

    private static String prettyTypeName(String name) {
        return name.equals("java.lang.Object") ? name + " or def" : name;
    }

    private Statement createGetterBodyArrayOrCloneable(FieldNode fNode) {
        final Expression fieldExpr = new VariableExpression(fNode);
        final Expression expression = cloneArrayOrCloneableExpr(fieldExpr);
        return safeExpression(fieldExpr, expression);
    }

    private Statement createGetterBodyDate(FieldNode fNode) {
        final Expression fieldExpr = new VariableExpression(fNode);
        final Expression expression = cloneDateExpr(fieldExpr);
        return safeExpression(fieldExpr, expression);
    }

    @SuppressWarnings("Unchecked")
    public static Object checkImmutable(String className, String fieldName, Object field) {
        if (field == null || field instanceof Enum || inImmutableList(field.getClass().getName())) return field;
        if (field instanceof Collection) return DefaultGroovyMethods.asImmutable((Collection) field);
        if (field.getClass().getAnnotation(MY_CLASS) != null) return field;
        final String typeName = field.getClass().getName();
        throw new RuntimeException(createErrorMessage(className, fieldName, typeName, "constructing"));
    }

    public static void checkPropNames(Object instance, Map<String, Object> args) {
        final MetaClass metaClass = InvokerHelper.getMetaClass(instance);
        for (String k : args.keySet()) {
            if (metaClass.hasProperty(instance, k) == null)
                throw new MissingPropertyException(k, instance.getClass());
        }
    }
}
