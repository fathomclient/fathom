package net.fathom.optimizations.core.opt.text;

/**
 * A bounded String-keyed table that allocates nothing on lookup or insert.
 *
 * <h2>Why not {@code LinkedHashMap}</h2>
 *
 * <p>The access-ordered LinkedHashMap this replaces relinks an entry on every
 * hit and allocates one on every insert, and it was asked a hundred-odd times a
 * frame from inside {@code getStringWidth}. This is two open-addressed arrays
 * — a current generation and the one before it — so a hit is a hash probe and
 * an insert is two array stores.
 *
 * <h2>Eviction</h2>
 *
 * <p>Generational rather than LRU. When the current generation is full it
 * becomes the old one and whatever the old one still held is dropped; a hit in
 * the old generation moves the entry forward. Anything used within the last
 * two generations survives, and the table never holds more than its capacity.
 * That is the property the workload needs — a scoreboard line measured every
 * frame is never evicted, a chat line from a minute ago is — without paying for
 * exact recency order.
 *
 * <p>An optional {@link Evictor} hears about every value that leaves, which is
 * how a table of GL display lists deletes them.
 *
 * <p>Not synchronised: one thread, the render thread.
 */
public final class StringTable<V> {

    /** Told about each value that leaves the table. */
    public interface Evictor<V> {
        void evicted(String key, V value);
    }

    /** Marks a slot whose entry moved to the current generation. Compared by identity. */
    private static final String MOVED = new String("<moved>");

    private final int generationCapacity;
    private final Evictor<V> evictor;

    private Generation current;
    private Generation previous;

    public StringTable(int capacity) {
        this(capacity, null);
    }

    public StringTable(int capacity, Evictor<V> evictor) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.generationCapacity = Math.max(1, capacity / 2);
        this.evictor = evictor;
        int slots = 2;
        while (slots < generationCapacity * 2) {
            slots <<= 1;
        }
        this.current = new Generation(slots);
        this.previous = new Generation(slots);
    }

    /** The value for {@code key}, or null. A null key is always absent. */
    @SuppressWarnings("unchecked")
    public V get(String key) {
        if (key == null) {
            return null;
        }
        int hash = mix(key.hashCode());
        int found = current.find(key, hash);
        if (found >= 0) {
            return (V) current.values[found];
        }
        found = previous.find(key, hash);
        if (found < 0) {
            return null;
        }
        V value = (V) previous.values[found];
        previous.keys[found] = MOVED;
        previous.values[found] = null;
        previous.live--;
        insert(key, hash, value);
        return value;
    }

    /** Store {@code value}, replacing any value already held for {@code key}. */
    @SuppressWarnings("unchecked")
    public void put(String key, V value) {
        if (key == null) {
            return;
        }
        int hash = mix(key.hashCode());
        int found = current.find(key, hash);
        if (found >= 0) {
            V old = (V) current.values[found];
            current.values[found] = value;
            if (old != value && old != null && evictor != null) {
                evictor.evicted(key, old);
            }
            return;
        }
        found = previous.find(key, hash);
        if (found >= 0) {
            V old = (V) previous.values[found];
            previous.keys[found] = MOVED;
            previous.values[found] = null;
            previous.live--;
            if (old != value && old != null && evictor != null) {
                evictor.evicted(key, old);
            }
        }
        insert(key, hash, value);
    }

    /** Forget everything, telling the evictor about each value. */
    public void clear() {
        drop(previous);
        drop(current);
    }

    public int size() {
        return current.live + previous.live;
    }

    private void insert(String key, int hash, V value) {
        if (current.used >= generationCapacity) {
            drop(previous);
            Generation retired = previous;
            previous = current;
            current = retired;
        }
        current.store(key, hash, value);
    }

    @SuppressWarnings("unchecked")
    private void drop(Generation generation) {
        String[] keys = generation.keys;
        Object[] values = generation.values;
        for (int index = 0; index < keys.length; index++) {
            String key = keys[index];
            if (key != null && key != MOVED && evictor != null && values[index] != null) {
                evictor.evicted(key, (V) values[index]);
            }
            keys[index] = null;
            values[index] = null;
        }
        generation.used = 0;
        generation.live = 0;
    }

    /** Spreads String hashes, whose low bits are poor for short keys. */
    private static int mix(int hash) {
        hash ^= hash >>> 16;
        hash *= 0x85EBCA6B;
        return hash ^ (hash >>> 13);
    }

    private static final class Generation {
        final String[] keys;
        final Object[] values;
        final int mask;
        /** Slots written this generation, including ones since moved. */
        int used;
        /** Slots still holding an entry. */
        int live;

        Generation(int slots) {
            keys = new String[slots];
            values = new Object[slots];
            mask = slots - 1;
        }

        int find(String key, int hash) {
            int index = hash & mask;
            while (true) {
                String held = keys[index];
                if (held == null) {
                    return -1;
                }
                if (held != MOVED && (held == key || held.equals(key))) {
                    return index;
                }
                index = (index + 1) & mask;
            }
        }

        void store(String key, int hash, Object value) {
            int index = hash & mask;
            while (keys[index] != null) {
                index = (index + 1) & mask;
            }
            keys[index] = key;
            values[index] = value;
            used++;
            live++;
        }
    }
}
