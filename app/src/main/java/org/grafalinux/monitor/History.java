package org.grafalinux.monitor;

import java.util.HashMap;
import java.util.Set;

/** Small ring buffers of recent values, kept only for sensors used on the dashboard. */
final class History {
    static final int N = 120;

    static final class Ring {
        final float[] v = new float[N];
        int head = 0;
        int count = 0;
        double peak = 0;

        void add(float x) {
            v[head] = x;
            head = (head + 1) % N;
            if (count < N) count++;
            peak = Math.max(x, peak * 0.995);
        }

        /** i = 0 is the oldest stored value. */
        float get(int i) {
            return v[(head - count + i + 2 * N) % N];
        }
    }

    private final HashMap<String, Ring> map = new HashMap<String, Ring>();

    void record(Sensors.Table t, Set<String> wanted) {
        for (String id : wanted) {
            Sensors.Sensor s = t.map.get(id);
            if (s == null) continue;
            Ring r = map.get(id);
            if (r == null) {
                r = new Ring();
                map.put(id, r);
            }
            r.add((float) s.value);
        }
    }

    Ring ring(String id) {
        return map.get(id);
    }

    double peak(String id) {
        Ring r = map.get(id);
        return r == null ? 0 : r.peak;
    }

    void clear() {
        map.clear();
    }
}
