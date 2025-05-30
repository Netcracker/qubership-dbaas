package org.qubership.cloud.dbaas;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;

public class TestUtils {
    public static <T> Instance<T> createInstance(T bean) {
        return new Instance<T>() {
            @Override
            public Instance<T> select(Annotation... annotations) {
                return null;
            }

            @Override
            public <U extends T> Instance<U> select(Class<U> aClass, Annotation... annotations) {
                return null;
            }

            @Override
            public <U extends T> Instance<U> select(TypeLiteral<U> typeLiteral, Annotation... annotations) {
                return null;
            }

            @Override
            public boolean isUnsatisfied() {
                return bean == null;
            }

            @Override
            public boolean isAmbiguous() {
                return false;
            }

            @Override
            public void destroy(T t) {

            }

            @Override
            public Handle<T> getHandle() {
                return null;
            }

            @Override
            public Iterable<? extends Handle<T>> handles() {
                return null;
            }

            @Override
            public T get() {
                if (bean == null) {
                    throw new UnsatisfiedResolutionException();
                } else {
                    return bean;
                }
            }

            @NotNull
            @Override
            public Iterator<T> iterator() {
                return List.of(bean).iterator();
            }
        };
    }
}
