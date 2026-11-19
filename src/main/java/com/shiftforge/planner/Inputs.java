package com.shiftforge.planner;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.shiftforge.planner.Domain.*;

/** Strict boundary validation, including complete matrix keys and integer-only quantities. */
public final class Inputs {
    private Inputs() {}
    public static Scenario scenario(JsonNode n) {
        object(n, "scenario", "schema_version", "name", "data_origin", "products", "cells", "processing_times", "shifts", "capacity", "demand");
        integer(n, "schema_version", 1, 1); text(n,"name",1,160,false);
        require("synthetic".equals(n.path("data_origin").textValue()), "data_origin must be synthetic");
        rows(n,"products",1,8, r -> { object(r,"product","id","initial_inventory_units"); id(r,"id"); integer(r,"initial_inventory_units",0,10000); });
        rows(n,"cells",1,6, r -> { object(r,"cell","id","crew_operators"); id(r,"id"); integer(r,"crew_operators",1,1000); });
        rows(n,"processing_times",0,48,r -> { object(r,"processing time","product_id","cell_id","minutes_per_unit"); id(r,"product_id"); id(r,"cell_id"); integer(r,"minutes_per_unit",1,1440); });
        rows(n,"shifts",1,20,r -> { object(r,"shift","id","index","duration_minutes","available_operators"); id(r,"id"); integer(r,"index",1,20); integer(r,"duration_minutes",1,1440); integer(r,"available_operators",0,1000); });
        rows(n,"capacity",1,120,r -> { object(r,"capacity","cell_id","shift_id","available_machine_minutes"); id(r,"cell_id"); id(r,"shift_id"); integer(r,"available_machine_minutes",0,1440); });
        rows(n,"demand",1,160,r -> { object(r,"demand","product_id","shift_id","due_units"); id(r,"product_id"); id(r,"shift_id"); integer(r,"due_units",0,10000); });
        Scenario s = Json.convert(n,Scenario.class);
        Set<String> ps=unique(s.products(),Product::id,"product ID"), cs=unique(s.cells(),Cell::id,"cell ID"), ts=unique(s.shifts(),Shift::id,"shift ID");
        Set<Integer> indices=unique(s.shifts(),Shift::index,"shift index");
        require(indices.equals(java.util.stream.IntStream.rangeClosed(1,ts.size()).boxed().collect(Collectors.toSet())),"Shift indices must be contiguous starting at 1");
        Set<Pair> processing=unique(s.processingTimes(),r->new Pair(r.productId(),r.cellId()),"processing pair");
        require(pairs(ps,cs).containsAll(processing),"Unknown product or cell in processing times");
        require(unique(s.capacity(),r->new Pair(r.cellId(),r.shiftId()),"capacity pair").equals(pairs(cs,ts)),"Capacity needs exactly one row per cell and shift");
        require(unique(s.demand(),r->new Pair(r.productId(),r.shiftId()),"demand pair").equals(pairs(ps,ts)),"Demand needs exactly one row per product and shift");
        Map<String,Integer> durations=s.shifts().stream().collect(Collectors.toMap(Shift::id,Shift::durationMinutes));
        for(Capacity c:s.capacity()) require(c.availableMachineMinutes()<=durations.get(c.shiftId()),"Machine minutes exceed shift duration");
        return s;
    }
    public static SolveRequest request(JsonNode n) {
        require(n!=null && n.isObject(),"Request must be an object");
        Set<String> allowed=Set.of("scenario","algorithm","solver_time_limit_seconds");
        n.fieldNames().forEachRemaining(k->require(allowed.contains(k),"Unknown request field: "+k));
        Scenario scenario=scenario(n.get("scenario"));
        String algorithm=n.has("algorithm")?n.path("algorithm").textValue():"gurobi";
        require("gurobi".equals(algorithm)||"greedy".equals(algorithm),"Algorithm must be gurobi or greedy");
        int limit=n.has("solver_time_limit_seconds")?integer(n,"solver_time_limit_seconds",1,60):10;
        return new SolveRequest(scenario,algorithm,limit);
    }
    static Set<Pair> pairs(Set<String> a,Set<String> b) { Set<Pair> out=new HashSet<>(); for(String x:a)for(String y:b)out.add(new Pair(x,y)); return out; }
    private static <T,K> Set<K> unique(List<T> rows,Function<T,K> key,String label) {
        Set<K> set=new HashSet<>(); for(T row:rows) require(set.add(key.apply(row)),"Duplicate "+label); return set;
    }
    private static void object(JsonNode n,String label,String...fields) {
        require(n!=null && n.isObject(),label+" must be an object");
        Set<String> actual=new HashSet<>(); n.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(fields)),label+" has missing or unknown fields");
    }
    private static void rows(JsonNode n,String key,int min,int max,java.util.function.Consumer<JsonNode> validate) {
        JsonNode a=n.get(key); require(a!=null&&a.isArray()&&a.size()>=min&&a.size()<=max,key+" length out of range"); a.forEach(validate);
    }
    private static int integer(JsonNode n,String key,int min,int max) {
        JsonNode v=n.get(key); require(v!=null&&v.isIntegralNumber()&&v.canConvertToInt(),key+" must be an integer");
        int i=v.intValue(); require(i>=min&&i<=max,key+" out of range"); return i;
    }
    private static void id(JsonNode n,String key) { text(n,key,1,64,true); }
    private static void text(JsonNode n,String key,int min,int max,boolean id) {
        JsonNode v=n.get(key); require(v!=null&&v.isTextual(),key+" must be text"); String t=v.textValue();
        require(t.codePointCount(0,t.length())>=min&&t.codePointCount(0,t.length())<=max,key+" length out of range");
        if(id) require(t.matches("[A-Za-z0-9_-]+"),key+" contains invalid characters");
    }
    static void require(boolean ok,String message) { if(!ok)throw new IllegalArgumentException(message); }
}
