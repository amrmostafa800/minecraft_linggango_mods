package com.linggango.memfix;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Adapter for Guava multimaps, reached reflectively so memfix does not pin a
 * Guava version. A multimap is not a {@code java.util.Map}, so the normal map
 * path would silently skip it; several pack mods keep player-keyed state in one.
 */
final class GuavaAccess {

    interface MultimapLike {
        Set<Object> keySetView();

        void removeAll(Object key);
    }

    private GuavaAccess() {
    }

    static MultimapLike multimapOf(Object o) {
        if (o == null) {
            return null;
        }
        if (!isMultimap(o.getClass())) {
            return null;
        }
        try {
            Method keySet = o.getClass().getMethod("keySet");
            Method removeAll = o.getClass().getMethod("removeAll", Object.class);
            keySet.setAccessible(true);
            removeAll.setAccessible(true);
            return new ReflectiveMultimap(o, keySet, removeAll);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isMultimap(Class<?> c) {
        for (Class<?> i : c.getInterfaces()) {
            if (i.getName().equals("com.google.common.collect.Multimap")) {
                return true;
            }
            if (isMultimap(i)) {
                return true;
            }
        }
        Class<?> sup = c.getSuperclass();
        return sup != null && isMultimap(sup);
    }

    private static final class ReflectiveMultimap implements MultimapLike {
        private final Object delegate;
        private final Method keySet;
        private final Method removeAll;

        ReflectiveMultimap(Object delegate, Method keySet, Method removeAll) {
            this.delegate = delegate;
            this.keySet = keySet;
            this.removeAll = removeAll;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<Object> keySetView() {
            try {
                return (Set<Object>) keySet.invoke(delegate);
            } catch (Throwable t) {
                return Set.of();
            }
        }

        @Override
        public void removeAll(Object key) {
            try {
                removeAll.invoke(delegate, key);
            } catch (Throwable ignored) {
            }
        }
    }
}
