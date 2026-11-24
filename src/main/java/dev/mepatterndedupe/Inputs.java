package dev.mepatterndedupe;

import java.math.BigInteger;
import java.util.*;

/** Order-independent, quantity-sensitive input signatures, including alternatives. */
public final class Inputs {
    public record Option<K>(K key, long amount) {}
    public record Ingredient<K>(List<Option<K>> alternatives, long multiplier) {}
    public record Choice<K>(K preferred, Map<K,BigInteger> ratios) {}
    public static <K> Map<Choice<K>,BigInteger> signature(List<Ingredient<K>> ingredients) {
        Map<Choice<K>,BigInteger> result=new HashMap<>();
        for(var input:ingredients) {
            if(input.multiplier()<=0 || input.alternatives().isEmpty()) throw new IllegalArgumentException("Invalid input");
            Map<K,BigInteger> amounts=new HashMap<>();
            BigInteger gcd=BigInteger.ZERO;
            for(var option:input.alternatives()) {
                if(option.key()==null || option.amount()<=0) throw new IllegalArgumentException("Invalid alternative");
                BigInteger amount=BigInteger.valueOf(option.amount());
                BigInteger old=amounts.putIfAbsent(option.key(),amount);
                if(old!=null && !old.equals(amount)) throw new IllegalArgumentException("Ambiguous alternative amounts");
                gcd=gcd.gcd(amount);
            }
            BigInteger scale=gcd;
            amounts.replaceAll((key,amount)->amount.divide(scale));
            var choice=new Choice<>(input.alternatives().get(0).key(),Map.copyOf(amounts));
            result.merge(choice,scale.multiply(BigInteger.valueOf(input.multiplier())),BigInteger::add);
        }
        return Map.copyOf(result);
    }
    public static <T,K,S> List<T> conflicts(List<T> rows, java.util.function.Function<T,K> output, java.util.function.Function<T,S> inputs) {
        Map<K,Set<S>> groups=new HashMap<>();
        for(T row:rows) groups.computeIfAbsent(output.apply(row),k->new HashSet<>()).add(inputs.apply(row));
        return rows.stream().filter(row->groups.get(output.apply(row)).size()>1).toList();
    }
}
