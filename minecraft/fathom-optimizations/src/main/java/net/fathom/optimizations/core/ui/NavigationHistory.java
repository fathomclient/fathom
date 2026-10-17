package net.fathom.optimizations.core.ui;

import java.util.LinkedList;

/** Exact, identity-based return targets for nested screens. */
public final class NavigationHistory<T> {

    private final LinkedList<Entry<T>> entries = new LinkedList<Entry<T>>();

    public void push(T current, T previous) {
        if (current == null) {
            throw new IllegalArgumentException("current screen must not be null");
        }
        entries.addLast(new Entry<T>(current, previous));
    }

    /**
     * Pop only when {@code current} is the exact object on top of the stack.
     * A successful result may contain a null target, meaning the world.
     */
    public Return<T> back(T current) {
        if (entries.isEmpty() || entries.getLast().current != current) {
            return Return.missing();
        }
        return Return.found(entries.removeLast().previous);
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private static final class Entry<T> {
        final T current;
        final T previous;

        Entry(T current, T previous) {
            this.current = current;
            this.previous = previous;
        }
    }

    /** Distinguishes "not found" from a found world/null return target. */
    public static final class Return<T> {
        private static final Return<?> MISSING = new Return<Object>(false, null);

        private final boolean found;
        private final T target;

        private Return(boolean found, T target) {
            this.found = found;
            this.target = target;
        }

        @SuppressWarnings("unchecked")
        static <T> Return<T> missing() {
            return (Return<T>) MISSING;
        }

        static <T> Return<T> found(T target) {
            return new Return<T>(true, target);
        }

        public boolean found() {
            return found;
        }

        public T target() {
            return target;
        }
    }
}
