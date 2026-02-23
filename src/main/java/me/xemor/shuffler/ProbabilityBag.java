package me.xemor.shuffler;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class ProbabilityBag {

    private double totalWeighting = 0;
    private Map<Material, Double> bag = new HashMap<>();

    public ProbabilityBag() {}

    public List<Entry> entries() {
        return entries(totalWeighting);
    }

    public List<Entry> entries(double weightingCap) {
        List<Entry> intermediary = bag.entrySet().stream().map((it) -> new Entry(it.getKey(), it.getValue() / totalWeighting))
                .filter((it) -> it.probability < weightingCap).toList();
        double sum = intermediary.stream().mapToDouble(Entry::probability).sum();
        return intermediary.stream().map((it) -> new Entry(it.material, it.probability / sum)).toList();
    }

    public void addWeighting(Material type) {
        addWeighting(type, 1);
    }

    public double get(Material type) {
        return bag.getOrDefault(type, 0D) / totalWeighting;
    }

    public double getWeighting(Material type) {
        return bag.getOrDefault(type, 0D);
    }

    public boolean contains(Material type) {
        return bag.containsKey(type);
    }

    public boolean containsAll(Collection<Material> types) {
        return bag.keySet().containsAll(types);
    }

    public ProbabilityBag addWeighting(Material type, double weighting) {
        bag.put(type, bag.getOrDefault(type, 0D) + weighting);
        totalWeighting += weighting;
        return this;
    }

    public ProbabilityBag maxWeighting(Material type, double weighting) {
        Double currentWeight = bag.get(type);
        if (currentWeight == null) {
            return addWeighting(type, weighting);
        }
        weighting = Math.max(currentWeight, weighting);
        bag.put(type, weighting);
        totalWeighting += weighting - currentWeight;
        return this;
    }

    public ProbabilityBag addWeighting(ProbabilityBag bag) {
        for (Map.Entry<Material, Double> entry : bag.bag.entrySet()) {
            addWeighting(entry.getKey(), entry.getValue());
        }
        return this;
    }

    public ProbabilityBag addWeighting(Collection<Material> types) {
        for (Material type : types) {
            addWeighting(type);
        }
        return this;
    }

    public SampleResult sample(double weightingCap) {
        List<Entry> sortedBag = entries(weightingCap).stream().sorted((it1, it2) -> {
            if (it1.probability() == it2.probability()) {
                return 0;
            }
            return it1.probability() > it2.probability() ? 1 : -1;
        }).toList();
        double p = ThreadLocalRandom.current().nextDouble(1.0);
        for (Entry entry : sortedBag) {
            if (p <= 0) {
                return new SampleResult(entry.material(), entry.probability());
            }
            p -= entry.probability();
            if (p <= 0) {
                return new SampleResult(entry.material(), entry.probability());
            }
        }
        throw new IllegalStateException("WEE WOO WEE WOO!");
    }

    public List<SampleResult> samples(int count, double weightingCap) {
        List<Entry> sortedBag = entries(weightingCap).stream()
                .sorted((it1, it2) -> {
                    if (it1.probability() == it2.probability()) {
                        return 0;
                    }
                    return it1.probability() > it2.probability() ? 1 : -1;
                }).toList();

        double p = ThreadLocalRandom.current().nextDouble(1.0);

        int index = -1;
        for (int i = 0; i < sortedBag.size(); i++) {
            Entry entry = sortedBag.get(i);
            if (p <= 0) {
                index = i;
                break;
            }
            p -= entry.probability;
            if (p <= 0) {
                index = i;
                break;
            }
        }
        
        if (index == -1) {
            throw new IllegalStateException("WEE WOO WEE WOO!");
        }

        return getSamplesAroundIndex(count, sortedBag, index);
    }

    private static @NotNull List<SampleResult> getSamplesAroundIndex(int count, List<Entry> sortedBag, int index) {
        List<SampleResult> results = new ArrayList<>();
        results.add(new SampleResult(sortedBag.get(index).material, sortedBag.get(index).probability));
        int leftPointer = index - 1;
        int rightPointer = index + 1;
        for (int i = 1; i < count && results.size() < count; i++) {
            if (i % 2 == 1 && rightPointer < sortedBag.size()) {
                results.add(new SampleResult(sortedBag.get(rightPointer).material(), sortedBag.get(rightPointer).probability()));
                rightPointer++;
            } else if (leftPointer >= 0) {
                results.add(new SampleResult(sortedBag.get(leftPointer).material(), sortedBag.get(leftPointer).probability()));
                leftPointer--;
            }
        }
        return results;
    }

    public record SampleResult(Material material, double weighting) {}
    public record Entry(Material material, double probability) {}

}
