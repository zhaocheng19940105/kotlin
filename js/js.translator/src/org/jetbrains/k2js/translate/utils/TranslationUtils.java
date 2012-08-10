/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.k2js.translate.utils;

import com.google.dart.compiler.backend.js.ast.*;
import com.google.dart.compiler.util.AstUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.calls.ResolvedCall;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ReceiverDescriptor;
import org.jetbrains.k2js.translate.context.TranslationContext;
import org.jetbrains.k2js.translate.general.Translation;

import java.util.ArrayList;
import java.util.List;

import static org.jetbrains.k2js.translate.utils.BindingUtils.getFunctionDescriptorForOperationExpression;
import static org.jetbrains.k2js.translate.utils.JsAstUtils.*;
import static org.jetbrains.k2js.translate.utils.JsDescriptorUtils.getDeclarationDescriptorForReceiver;
import static org.jetbrains.k2js.translate.utils.JsDescriptorUtils.getExpectedReceiverDescriptor;

/**
 * @author Pavel Talanov
 */
public final class TranslationUtils {

    private static final JsNameRef UNDEFINED_LITERAL = AstUtil.newQualifiedNameRef("undefined");

    private TranslationUtils() {
    }

    @NotNull
    public static JsPropertyInitializer translateFunctionAsEcma5PropertyDescriptor(@NotNull JsFunction function,
            @NotNull FunctionDescriptor descriptor,
            @NotNull TranslationContext context) {
        if (JsDescriptorUtils.isExtension(descriptor)) {
            return translateExtensionFunctionAsEcma5PropertyDescriptor(function, descriptor, context);
        }
        else {
            JsStringLiteral getOrSet = context.program().getStringLiteral(descriptor instanceof PropertyGetterDescriptor ? "get" : "set");
            return new JsPropertyInitializer(getOrSet, function);
        }
    }

    @NotNull
    private static JsPropertyInitializer translateExtensionFunctionAsEcma5PropertyDescriptor(@NotNull JsFunction function,
            @NotNull FunctionDescriptor descriptor, @NotNull TranslationContext context) {
        JsObjectLiteral meta = JsAstUtils.createDataDescriptor(function, descriptor.getModality().isOverridable());
        return new JsPropertyInitializer(context.getNameForDescriptor(descriptor).makeRef(), meta);
    }

    @NotNull
    public static JsBinaryOperation notNullCheck(@NotNull TranslationContext context,
            @NotNull JsExpression expressionToCheck) {
        JsBinaryOperation notNull = inequality(expressionToCheck, JsLiteral.NULL);
        JsBinaryOperation notUndefined = inequality(expressionToCheck, UNDEFINED_LITERAL);
        return and(notNull, notUndefined);
    }

    @NotNull
    public static JsBinaryOperation isNullCheck(@NotNull TranslationContext context,
            @NotNull JsExpression expressionToCheck) {
        JsBinaryOperation isNull = equality(expressionToCheck, JsLiteral.NULL);
        JsBinaryOperation isUndefined = equality(expressionToCheck, UNDEFINED_LITERAL);
        return or(isNull, isUndefined);
    }

    @NotNull
    public static List<JsExpression> translateArgumentList(@NotNull TranslationContext context,
            @NotNull List<? extends ValueArgument> jetArguments) {
        List<JsExpression> jsArguments = new ArrayList<JsExpression>();
        for (ValueArgument argument : jetArguments) {
            jsArguments.add(translateArgument(context, argument));
        }
        return jsArguments;
    }

    @NotNull
    private static JsExpression translateArgument(@NotNull TranslationContext context, @NotNull ValueArgument argument) {
        JetExpression jetExpression = argument.getArgumentExpression();
        assert jetExpression != null : "Argument with no expression";
        return Translation.translateAsExpression(jetExpression, context);
    }

    @NotNull
    public static JsNameRef backingFieldReference(@NotNull TranslationContext context,
            @NotNull PropertyDescriptor descriptor) {
        JsName backingFieldName = context.getNameForDescriptor(descriptor);
        return new JsNameRef(backingFieldName, JsLiteral.THIS);
    }

    @NotNull
    public static JsExpression assignmentToBackingField(@NotNull TranslationContext context,
            @NotNull PropertyDescriptor descriptor,
            @NotNull JsExpression assignTo) {
        JsNameRef backingFieldReference = backingFieldReference(context, descriptor);
        return assignment(backingFieldReference, assignTo);
    }

    @Nullable
    public static JsExpression translateInitializerForProperty(@NotNull JetProperty declaration,
            @NotNull TranslationContext context) {
        JsExpression jsInitExpression = null;
        JetExpression initializer = declaration.getInitializer();
        if (initializer != null) {
            jsInitExpression = Translation.translateAsExpression(initializer, context);
        }
        return jsInitExpression;
    }

    @NotNull
    public static JsNameRef getQualifiedReference(@NotNull TranslationContext context, @NotNull DeclarationDescriptor descriptor) {
        return new JsNameRef(context.getNameForDescriptor(descriptor), context.getQualifierForDescriptor(descriptor));
    }

    @NotNull
    public static List<JsExpression> translateExpressionList(@NotNull TranslationContext context,
            @NotNull List<JetExpression> expressions) {
        List<JsExpression> result = new ArrayList<JsExpression>();
        for (JetExpression expression : expressions) {
            result.add(Translation.translateAsExpression(expression, context));
        }
        return result;
    }

    @NotNull
    public static JsExpression translateBaseExpression(@NotNull TranslationContext context,
            @NotNull JetUnaryExpression expression) {
        JetExpression baseExpression = PsiUtils.getBaseExpression(expression);
        return Translation.translateAsExpression(baseExpression, context);
    }

    @NotNull
    public static JsExpression translateLeftExpression(@NotNull TranslationContext context,
            @NotNull JetBinaryExpression expression) {
        return Translation.translateAsExpression(expression.getLeft(), context);
    }

    @NotNull
    public static JsExpression translateRightExpression(@NotNull TranslationContext context,
            @NotNull JetBinaryExpression expression) {
        JetExpression rightExpression = expression.getRight();
        assert rightExpression != null : "Binary expression should have a right expression";
        return Translation.translateAsExpression(rightExpression, context);
    }

    public static boolean hasCorrespondingFunctionIntrinsic(@NotNull TranslationContext context,
            @NotNull JetOperationExpression expression) {
        FunctionDescriptor operationDescriptor = getFunctionDescriptorForOperationExpression(context.bindingContext(), expression);

        if (operationDescriptor == null) return true;
        if (context.intrinsics().getFunctionIntrinsics().getIntrinsic(operationDescriptor).exists()) return true;

        return false;
    }

    @Nullable
    public static JsExpression resolveThisObjectForResolvedCall(@NotNull ResolvedCall<?> call,
            @NotNull TranslationContext context) {
        ReceiverDescriptor thisObject = call.getThisObject();
        if (!thisObject.exists()) {
            return null;
        }
        DeclarationDescriptor expectedThisDescriptor = getDeclarationDescriptorForReceiver(thisObject);
        return getThisObject(context, expectedThisDescriptor);
    }

    public static void defineModule(@NotNull TranslationContext context, @NotNull List<JsStatement> statements,
            String moduleId) {
        statements.add(new JsInvocation(context.namer().kotlin("defineModule"),
                                             context.program().getStringLiteral(moduleId),
                                             context.scope().declareName("_").makeRef()).makeStmt());
    }
}
