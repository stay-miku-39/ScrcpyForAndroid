package org.scrcpy.android;

import android.content.SharedPreferences;
import java.util.*;

/** Page-local preferences. Only dirty keys are persisted; live window geometry is never rolled back. */
final class SettingsDraft implements SharedPreferences {
    private final SharedPreferences saved;
    private final Map<String, Object> values = new HashMap<>(), changes = new HashMap<>();
    private final Set<String> removed = new HashSet<>();
    private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();
    SettingsDraft(SharedPreferences saved) { this.saved = saved; values.putAll(saved.getAll()); }
    void persist() {
        SharedPreferences.Editor edit = saved.edit();
        for (String key : removed) edit.remove(key);
        for (Map.Entry<String, Object> e : changes.entrySet()) put(edit, e.getKey(), e.getValue());
        edit.apply(); changes.clear(); removed.clear();
    }
    private static void put(SharedPreferences.Editor edit, String key, Object v) {
        if (v instanceof String) edit.putString(key, (String)v);
        else if (v instanceof Boolean) edit.putBoolean(key, (Boolean)v);
        else if (v instanceof Integer) edit.putInt(key, (Integer)v);
        else if (v instanceof Long) edit.putLong(key, (Long)v);
        else if (v instanceof Float) edit.putFloat(key, (Float)v);
        else if (v instanceof Set) { @SuppressWarnings("unchecked") Set<String> strings=(Set<String>)v; edit.putStringSet(key, strings); }
    }
    public Map<String, ?> getAll() { return new HashMap<>(values); }
    public boolean contains(String key) { return values.containsKey(key); }
    public String getString(String key, String def) { return (String)values.getOrDefault(key, def); }
    @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> def) { Set<String> v=(Set<String>)values.getOrDefault(key, def); return v == null ? null : new HashSet<>(v); }
    public int getInt(String key, int def) { return (Integer)values.getOrDefault(key, def); }
    public long getLong(String key, long def) { return (Long)values.getOrDefault(key, def); }
    public float getFloat(String key, float def) { return (Float)values.getOrDefault(key, def); }
    public boolean getBoolean(String key, boolean def) { return (Boolean)values.getOrDefault(key, def); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.add(listener); }
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.remove(listener); }
    public Editor edit() { return new Editor() {
        private final Map<String, Object> pending = new HashMap<>(); private boolean clear;
        public Editor putString(String k, String v) { pending.put(k,v); return this; }
        public Editor putStringSet(String k, Set<String> v) { pending.put(k,v == null ? null : new HashSet<>(v)); return this; }
        public Editor putInt(String k, int v) { pending.put(k,v); return this; }
        public Editor putLong(String k, long v) { pending.put(k,v); return this; }
        public Editor putFloat(String k, float v) { pending.put(k,v); return this; }
        public Editor putBoolean(String k, boolean v) { pending.put(k,v); return this; }
        public Editor remove(String k) { pending.put(k,null); return this; }
        public Editor clear() { clear=true; return this; }
        public boolean commit() { apply(); return true; }
        public void apply() {
            if (clear) { removed.addAll(values.keySet()); values.clear(); changes.clear(); }
            for (Map.Entry<String, Object> e : pending.entrySet()) {
                String k=e.getKey(); Object v=e.getValue();
                if (v == null) { values.remove(k); changes.remove(k); removed.add(k); }
                else { values.put(k,v); changes.put(k,v); removed.remove(k); }
                for (OnSharedPreferenceChangeListener listener : listeners) listener.onSharedPreferenceChanged(SettingsDraft.this,k);
            }
        }
    }; }
}
