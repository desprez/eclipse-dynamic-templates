/*
 * Copyright (c) Feb 28, 2018 StarChart Labs Authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    romeara - initial API and implementation and/or initial documentation
 *    desprez - add field type and setter.
 */
package org.starchartlabs.eclipse.template.dynamic.resolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.corext.template.java.CompilationUnitContext;
import org.eclipse.jface.text.templates.TemplateContext;
import org.eclipse.jface.text.templates.TemplateVariable;
import org.eclipse.jface.text.templates.TemplateVariableResolver;

/**
 * Fields resolver which allows defining a template to fill for every Java "bean" field
 *
 * <p>
 * In this context, a "bean" field is defined as a field which has a matching method called "get(Fieldname)". For
 * booleans, "is(Fieldname)" will also be considered a match
 *
 * <p>
 * The template variable resolved by this class is expected to be of the form:
 *
 * <pre>
 * ${id:enclosed_bean_fields(template, separator)}
 * </pre>
 *
 * Where the user-provided values are:
 * <ul>
 * <li>id - identifier (unique within template) and default filler value in case of error</li>
 * <li>template - Line to substitute per bean-field. May use ${name} within to substitute field name, and ${getter} to
 * substitute getter method (including parentheses)</li>
 * <li>separator - value, if any, to place between each occurrence of the substituted template. May use ${newline} to
 * substitute in a System.lineSeparator(). Resulting new-lines will be overridden by code formatter if it is enabled for
 * the template</li>
 * </ul>
 *
 * <p>
 * This class is intended to be extensible
 *
 * <p>
 * References:
 * <ul>
 * <li>org.eclipse.jface.text.templates.TemplateVariable</li>
 * <li>org.eclipse.jdt.internal.corext.template.java.CompilationUnitContextType</li>
 * <li>org.eclipse.jdt.internal.corext.template.java.JavaContextType</li>
 * <li>https://stackoverflow.com/questions/350600/eclipse-custom-variable-for-java-code-templates</li>
 * <li>https://help.eclipse.org/mars/index.jsp?topic=%2Forg.eclipse.platform.doc.isv%2Freference%2Fapi%2Forg%2Feclipse%2Fjface%2Ftext%2Ftemplates%2FTemplateVariableResolver.html</li>
 * <li>http://help.eclipse.org/kepler/index.jsp?topic=%2Forg.eclipse.jdt.doc.user%2Fconcepts%2Fconcept-template-variables.htm</li>
 * </ul>
 *
 * @author romeara
 * @since 0.1.0
 */
@SuppressWarnings("restriction")
public class EnclosedBeanFieldsResolver extends TemplateVariableResolver {

    protected static final String NAME_PLACEHOLDER = "\\$\\{name\\}";

    protected static final String GETTER_PLACEHOLDER = "\\$\\{getter\\}";

    protected static final String SETTER_PLACEHOLDER = "\\$\\{setter\\}";

    protected static final String TYPE_PLACEHOLDER = "\\$\\{type\\}";

    protected static final String NEWLINE_PLACEHOLDER = "\\$\\{newline\\}";

    private static final Set<String> BOOLEAN_TYPE_SIGNATURES = Stream.of("Z", "QBoolean;").collect(Collectors.toSet());

    @Override
    public void resolve(final TemplateVariable variable, final TemplateContext context) {
        if (context instanceof CompilationUnitContext) {
            final CompilationUnitContext jc = (CompilationUnitContext) context;

            final String[] bindings = resolveAll(jc, variable.getVariableType().getParams());

            // Store the result, and if resolved set unambiguous to true to avoid trying to get user input
            if (bindings != null) {
                variable.setValues(bindings);
                variable.setUnambiguous(true);
                variable.setResolved(true);
            } else {
                super.resolve(variable, context);
            }
        } else {
            super.resolve(variable, context);
        }
    }

    /**
     * Returns all possible bindings available in <code>context</code>, taking into account variable parameters
     *
     * @param context
     *            The Java compilation unit context in which to resolve the type
     * @param variableParameters
     * @return an array of possible bindings of this type in <code>context</code>, null if binding was unsuccessful
     */
    protected String[] resolveAll(final CompilationUnitContext context, final List<String> variableParameters) {
        String[] result = null;

        if (variableParameters.size() == 2) {
            final String template = variableParameters.get(0);
            final String separator = variableParameters.get(1).replaceAll(NEWLINE_PLACEHOLDER, System.lineSeparator());

            final List<String> lines = new ArrayList<>();

            for (final Entry<String, FieldInfo> entry : getBeanPairs(context).entrySet()) {

                final FieldInfo fieldInfo = entry.getValue();

                lines.add(template //
                        .replaceAll(NAME_PLACEHOLDER, entry.getKey()) //
                        .replaceAll(GETTER_PLACEHOLDER, fieldInfo.getGetter()) //
                        .replaceAll(SETTER_PLACEHOLDER, fieldInfo.getSetter()) //
                        .replaceAll(TYPE_PLACEHOLDER, fieldInfo.getType()) //
                        );
            }

            final String value = lines.stream().collect(Collectors.joining(separator));
            result = new String[] { value };
        }

        return result;
    }

    /**
     * Finds all pairs of fields to methods which follow a pre-defined "bean" pattern. Pairs match this pattern if a
     * field has a corresponding method named "get(fieldname)". For booleans, "is(fieldname)" is also permissible. In
     * both cases the field name is expected to be capitalized
     *
     * @param context
     *            Information about the compilation unit the template is being inserted into
     * @return A mapping of any bean fields to the name of the method that matched
     */
    protected Map<String, FieldInfo> getBeanPairs(final CompilationUnitContext context) {
        Objects.requireNonNull(context);

        final Map<String, FieldInfo> result = new LinkedHashMap<String, FieldInfo>();

        try {
            final IType type = (IType) context.findEnclosingElement(IJavaElement.TYPE);
            final Set<String> methodLookup = getCandidateMethods(type);

            for (final IField field : type.getFields()) {
                final String capitalizedName = capitalizeFirstLetter(field.getElementName());

                final String getter = "get" + capitalizedName;
                final String isGetter = "is" + capitalizedName;

                final FieldInfo fieldInfo  =  new FieldInfo();
                fieldInfo.setSetter("set" + capitalizedName);
                fieldInfo.setType(Signature.getSignatureSimpleName(field.getTypeSignature()));

                if (methodLookup.contains(getter)) {
                    fieldInfo.setGetter( getter + "()");
                } else if (isBooleanField(field) && methodLookup.contains(isGetter)) {
                    fieldInfo.setGetter( isGetter + "()");
                }

                result.put(field.getElementName(), fieldInfo);
            }
        } catch (final JavaModelException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * Finds and returns any methods within the provided type which match the expected pattern of a "getter". Matching
     * methods start with either "get" or "is", and have no parameters
     *
     * @param type
     *            Eclipse JDT representation of the type the template is being inserted into
     * @return Set of method names matching "getter" criteria
     * @throws JavaModelException
     *             If there is an error reading Java model information from the type
     */
    private Set<String> getCandidateMethods(final IType type) throws JavaModelException {
        Objects.requireNonNull(type);

        final IMethod[] methods = type.getMethods();
        final Set<String> methodLookup = new HashSet<>();

        for (final IMethod method : methods) {
            if (method.getParameterNames().length == 0
                    && (method.getElementName().startsWith("get") || method.getElementName().startsWith("is"))) {
                methodLookup.add(method.getElementName());
            }
        }

        return methodLookup;
    }

    /**
     * Determines if a field represents a primitive or object boolean
     *
     * @param field
     *            The field to check
     * @return True if the field represents a form of Java boolean, false otherwise
     * @throws JavaModelException
     *             If there is an error reading Java model information from the field
     */
    private boolean isBooleanField(final IField field) throws JavaModelException {
        Objects.requireNonNull(field);

        return BOOLEAN_TYPE_SIGNATURES.contains(field.getTypeSignature());
    }

    /**
     * Capitalizes the first letter in the provided string
     *
     * @param input
     *            The string to capitalize
     * @return The capitalized string
     */
    private String capitalizeFirstLetter(final String input) {
        Objects.requireNonNull(input);

        String result = input;

        if(!input.isEmpty()) {
            final String first = new String(input.substring(0, 1));
            result = input.replaceFirst(first, first.toUpperCase());
        }

        return result;
    }


}
