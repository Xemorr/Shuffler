package me.xemor.shuffler.game;

import org.bukkit.Material;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SetOfMapInteger {
    // Maps a Set of Materials to the specific Map of Material counts
    private final Map<Set<Material>, Map<Material, Integer>> storage = new HashMap<>();

    public void add(Map<Material, Integer> newMap) {
        Set<Material> materials = newMap.keySet();

        // 1. If we don't have this specific combination of materials, just add it.
        if (!storage.containsKey(materials)) {
            storage.put(materials, new HashMap<>(newMap));
            return;
        }

        // 2. If we DO have it, compare values
        Map<Material, Integer> existingMap = storage.get(materials);

        if (isStrictlySmallerOrEqual(newMap, existingMap)) {
            // New map is better (smaller), so replace the old one
            storage.put(materials, new HashMap<>(newMap));
        }
        // Else: existing is smaller or they are incomparable, so we do nothing
    }

    public void addAll(Collection<Map<Material, Integer>> newMaps) {
        newMaps.forEach(this::add);
    }

    private boolean isStrictlySmallerOrEqual(Map<Material, Integer> candidate, Map<Material, Integer> target) {
        for (Material material : candidate.keySet()) {
            if (candidate.get(material) > target.get(material)) {
                return false;
            }
        }
        return true;
    }

    public Collection<Map<Material, Integer>> getAllOptimalMaps() {
        return storage.values();
    }

    public int size() {
        return storage.size();
    }
}