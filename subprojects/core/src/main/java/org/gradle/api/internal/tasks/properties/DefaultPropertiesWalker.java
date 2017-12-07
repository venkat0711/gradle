/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package org.gradle.api.internal.tasks.properties;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import org.gradle.api.GradleException;
import org.gradle.api.NonNullApi;
import org.gradle.api.internal.tasks.PropertySpecFactory;
import org.gradle.api.internal.tasks.TaskValidationContext;
import org.gradle.api.internal.tasks.ValidationAction;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.internal.Factory;
import org.gradle.internal.UncheckedException;
import org.gradle.util.DeferredUtil;
import org.gradle.util.DeprecationLogger;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.gradle.api.internal.tasks.TaskValidationContext.Severity.ERROR;
import static org.gradle.api.internal.tasks.TaskValidationContext.Severity.INFO;

@NonNullApi
public class DefaultPropertiesWalker implements PropertiesWalker {

    private final PropertyMetadataStore propertyMetadataStore;

    public DefaultPropertiesWalker(PropertyMetadataStore propertyMetadataStore) {
        this.propertyMetadataStore = propertyMetadataStore;
    }

    @Override
    public void visitProperties(PropertySpecFactory specFactory, PropertyVisitor visitor, Object bean) {
        Queue<PropertyNode> queue = new ArrayDeque<PropertyNode>();
        queue.add(new PropertyNode(null, bean));
        boolean cacheable = bean.getClass().isAnnotationPresent(CacheableTask.class);
        while (!queue.isEmpty()) {
            PropertyNode node = queue.remove();
            detectProperties(node, node.getBean().getClass(), queue, visitor, specFactory, cacheable);
        }
    }

    private <T> void detectProperties(PropertyNode node, Class<T> type, Queue<PropertyNode> queue, PropertyVisitor visitor, PropertySpecFactory inputs, boolean cacheable) {
        final Set<PropertyMetadata> typeMetadata = propertyMetadataStore.getTypeMetadata(type);
        for (PropertyMetadata propertyMetadata : typeMetadata) {
            PropertyValueVisitor propertyValueVisitor = propertyMetadata.getPropertyValueVisitor();
            String propertyName = node.getQualifiedPropertyName(propertyMetadata.getFieldName());
            if (propertyValueVisitor == null) {
                if (!Modifier.isPrivate(propertyMetadata.getMethod().getModifiers())) {
                    visitor.visitValidationMessage(INFO, propertyValidationMessage(propertyName, "is not annotated with an input or output annotation"));
                }
                continue;
            }
            Object bean = node.getBean();
            PropertyValue propertyValue = new DefaultPropertyValue(propertyName, propertyMetadata.getAnnotations(), bean, propertyMetadata.getMethod(), cacheable);
            propertyValueVisitor.visitPropertyValue(propertyValue, visitor, inputs);
            for (String validationMessage : propertyMetadata.getValidationMessages()) {
                visitor.visitValidationMessage(INFO, propertyValue.validationMessage(validationMessage));
            }
            if (propertyValue.isAnnotationPresent(Nested.class)) {
                try {
                    Object nestedBean = propertyValue.getValue();
                    if (nestedBean != null) {
                        queue.add(new PropertyNode(propertyName, nestedBean));
                    }
                } catch (Exception e) {
                    // No nested bean
                }
            }
        }
    }

    private static String propertyValidationMessage(String propertyName, String message) {
        return String.format("property '%s' %s", propertyName, message);
    }

    private class PropertyNode {
        private final String parentPropertyName;
        private final Object bean;

        public PropertyNode(@Nullable String parentPropertyName, Object bean) {
            this.parentPropertyName = parentPropertyName;
            this.bean = bean;
        }

        public Object getBean() {
            return bean;
        }

        public String getQualifiedPropertyName(String propertyName) {
            return parentPropertyName == null ? propertyName : parentPropertyName + "." + propertyName;
        }
    }

    private static class DefaultPropertyValue implements PropertyValue {
        private final String propertyName;
        private final List<Annotation> annotations;
        private final Object instance;
        private final Method method;
        private final boolean cacheable;
        private final Supplier<Object> valueSupplier = Suppliers.memoize(new Supplier<Object>() {
            @Override
            @Nullable
            public Object get() {
                Object value = DeprecationLogger.whileDisabled(new Factory<Object>() {
                    public Object create() {
                        try {
                            return method.invoke(instance);
                        } catch (InvocationTargetException e) {
                            throw UncheckedException.throwAsUncheckedException(e.getCause());
                        } catch (Exception e) {
                            throw new GradleException(String.format("Could not call %s.%s() on %s", method.getDeclaringClass().getSimpleName(), method.getName(), instance), e);
                        }
                    }
                });
                return value instanceof Provider ? ((Provider<?>) value).getOrNull() : value;
            }
        });

        public DefaultPropertyValue(String propertyName, List<Annotation> annotations, Object instance, Method method, boolean cacheable) {
            this.propertyName = propertyName;
            this.annotations = ImmutableList.copyOf(annotations);
            this.instance = instance;
            this.method = method;
            this.cacheable = cacheable;
            method.setAccessible(true);
        }

        @Override
        public String getPropertyName() {
            return propertyName;
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
            return getAnnotation(annotationType) != null;
        }

        @Nullable
        @Override
        public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
            for (Annotation annotation : annotations) {
                if (annotationType.equals(annotation.annotationType())) {
                    return annotationType.cast(annotation);
                }
            }
            return null;
        }

        @Override
        public boolean isOptional() {
            return isAnnotationPresent(Optional.class);
        }

        @Nullable
        @Override
        public Object getValue() {
            return valueSupplier.get();
        }

        @Override
        public Class<?> getDeclaredType() {
            return method.getReturnType();
        }

        @Override
        public boolean isCacheable() {
            return cacheable;
        }

        @Override
        public String validationMessage(String message) {
            return propertyValidationMessage(getPropertyName(), message);
        }

        @Nullable
        @Override
        public Object call() {
            return getValue();
        }

        @Override
        public void validate(String propertyName, boolean optional, ValidationAction valueValidator, TaskValidationContext context) {
            Object unpacked = DeferredUtil.unpack(getValue());
            if (unpacked == null) {
                if (!optional) {
                    context.recordValidationMessage(ERROR, String.format("No value has been specified for property '%s'.", propertyName));
                }
            } else {
                valueValidator.validate(propertyName, unpacked, context, ERROR);
            }
        }
    }
}
