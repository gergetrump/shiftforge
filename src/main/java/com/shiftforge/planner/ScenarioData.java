package com.shiftforge.planner;

import java.util.*;
import static com.shiftforge.planner.Domain.*;

/** Read-only indexes built from an already validated scenario. */
final class ScenarioData {
    final Scenario scenario;
    final List<String> products, cells, shifts;
    final Map<Pair,Integer> rates=new HashMap<>(), capacities=new HashMap<>(), demand=new HashMap<>();
    final Map<String,Integer> crews=new HashMap<>(), initial=new HashMap<>(), useful=new HashMap<>();
    ScenarioData(Scenario s) {
        scenario=s;
        products=s.products().stream().map(Product::id).sorted().toList();
        cells=s.cells().stream().map(Cell::id).sorted().toList();
        shifts=s.orderedShifts().stream().map(Shift::id).toList();
        s.processingTimes().forEach(r->rates.put(new Pair(r.productId(),r.cellId()),r.minutesPerUnit()));
        s.capacity().forEach(r->capacities.put(new Pair(r.cellId(),r.shiftId()),r.availableMachineMinutes()));
        s.demand().forEach(r->demand.put(new Pair(r.productId(),r.shiftId()),r.dueUnits()));
        s.cells().forEach(r->crews.put(r.id(),r.crewOperators()));
        s.products().forEach(r->{initial.put(r.id(),r.initialInventoryUnits()); useful.put(r.id(),Math.max(0,s.demand().stream().filter(d->d.productId().equals(r.id())).mapToInt(Demand::dueUnits).sum()-r.initialInventoryUnits()));});
    }
    int rate(String p,String c) { return rates.getOrDefault(new Pair(p,c),0); }
    int capacity(String c,String t) { return capacities.get(new Pair(c,t)); }
    int demand(String p,String t) { return demand.get(new Pair(p,t)); }
}
