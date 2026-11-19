package com.shiftforge.planner;

import java.util.*;
import static com.shiftforge.planner.Domain.*;

/** Deterministic due-demand dispatch. Deliberately does not preproduce for future demand. */
public final class GreedyPlanner {
    private GreedyPlanner() {}
    public static Solution plan(Scenario s) {
        ScenarioData d=new ScenarioData(s);
        Map<String,Integer> net=new HashMap<>(d.initial),remaining=new HashMap<>(d.useful);
        Map<Triple,Integer> x=new HashMap<>();
        for(Shift t:s.orderedShifts()) {
            Map<String,Integer> minutes=new HashMap<>(),shortage=new HashMap<>();
            for(String c:d.cells)minutes.put(c,d.capacity(c,t.id()));
            for(String p:d.products)shortage.put(p,Math.max(0,d.demand(p,t.id())-net.get(p)));
            Set<String> active=new HashSet<>(); int staff=t.availableOperators();
            List<String> order=new ArrayList<>(d.products);
            order.sort(Comparator.<String>comparingLong(p->d.cells.stream().filter(c->d.rate(p,c)>0&&minutes.get(c)>=d.rate(p,c)).count())
                .thenComparing(p->-shortage.get(p)).thenComparing(Comparator.naturalOrder()));
            for(String p:order) {
                List<String> cells=d.cells.stream().filter(c->d.rate(p,c)>0)
                    .sorted(Comparator.<String,Boolean>comparing(c->!active.contains(c)).thenComparingInt(c->d.rate(p,c)).thenComparing(Comparator.naturalOrder())).toList();
                for(String c:cells) {
                    if(!active.contains(c)&&d.crews.get(c)>staff)continue;
                    int q=Math.min(shortage.get(p),Math.min(minutes.get(c)/d.rate(p,c),remaining.get(p)));
                    if(q<=0)continue;
                    if(active.add(c))staff-=d.crews.get(c);
                    x.put(new Triple(p,c,t.id()),q);
                    minutes.compute(c,(k,v)->v-q*d.rate(p,c)); shortage.compute(p,(k,v)->v-q); remaining.compute(p,(k,v)->v-q);
                }
            }
            for(String p:d.products) {
                int made=0; for(String c:d.cells)made+=x.getOrDefault(new Triple(p,c,t.id()),0);
                net.put(p,net.get(p)+made-d.demand(p,t.id()));
            }
        }
        return Plans.make(s,x);
    }
}
