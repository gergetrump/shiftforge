package com.shiftforge.planner;

import java.util.*;
import java.util.function.Function;
import static com.shiftforge.planner.Domain.*;

/** Arithmetic checking has no dependency on Gurobi or the optimization formulation. */
public final class Plans {
    private Plans() {}
    public static Solution make(Scenario s,Map<Triple,Integer> x) { return make(s,x,null,null); }
    public static Solution make(Scenario s,Map<Triple,Integer> x,Map<Pair,Integer> suppliedActivity,Map<Pair,Stock> suppliedStock) {
        ScenarioData d=new ScenarioData(s);
        List<Production> production=new ArrayList<>();
        for(String p:d.products)for(String c:d.cells)for(String t:d.shifts)
            production.add(new Production(p,c,t,x.getOrDefault(new Triple(p,c,t),0)));
        Map<Pair,Integer> y=new HashMap<>();
        for(String c:d.cells)for(String t:d.shifts) {
            Pair k=new Pair(c,t);
            int active=d.products.stream().anyMatch(p->x.getOrDefault(new Triple(p,c,t),0)!=0)?1:0;
            y.put(k,suppliedActivity==null?active:suppliedActivity.get(k));
        }
        Map<Pair,Stock> stock=new HashMap<>();
        for(String p:d.products) {
            int net=d.initial.get(p);
            for(String t:d.shifts) {
                for(String c:d.cells) net+=x.getOrDefault(new Triple(p,c,t),0);
                net-=d.demand(p,t);
                Pair k=new Pair(p,t);
                stock.put(k,suppliedStock==null?new Stock(p,t,Math.max(net,0),Math.max(-net,0)):suppliedStock.get(k));
            }
        }
        List<Activity> activity=new ArrayList<>();
        List<Stock> stocks=new ArrayList<>();
        List<CellUsage> usage=new ArrayList<>();
        List<Staffing> staffing=new ArrayList<>();
        for(String c:d.cells)for(String t:d.shifts) {
            activity.add(new Activity(c,t,y.get(new Pair(c,t))));
            long used=0; for(String p:d.products) used+=(long)d.rate(p,c)*x.getOrDefault(new Triple(p,c,t),0);
            int available=d.capacity(c,t);
            usage.add(new CellUsage(c,t,used,available,ratio(used,available)));
        }
        for(String p:d.products)for(String t:d.shifts) stocks.add(stock.get(new Pair(p,t)));
        for(Shift t:s.orderedShifts()) {
            int reserved=0; for(String c:d.cells) reserved+=d.crews.get(c)*y.get(new Pair(c,t.id()));
            staffing.add(new Staffing(t.id(),reserved,t.availableOperators(),ratio(reserved,t.availableOperators())));
        }
        long objective=stocks.stream().mapToLong(Stock::backlogUnits).sum();
        long used=usage.stream().mapToLong(CellUsage::usedMinutes).sum(), available=usage.stream().mapToLong(CellUsage::availableMinutes).sum();
        long finalBacklog=d.products.stream().mapToLong(p->stock.get(new Pair(p,d.shifts.getLast())).backlogUnits()).sum();
        Metrics metrics=new Metrics(objective,finalBacklog,activity.stream().mapToLong(Activity::active).sum(),
            stocks.stream().mapToLong(Stock::inventoryUnits).sum(),production.stream().mapToLong(Production::units).sum(),
            used,available,ratio(used,available),usage,staffing);
        return new Solution(objective,production,activity,stocks,metrics);
    }
    private static Double ratio(long a,long b) { return b==0?null:(double)a/b; }

    public static Validation check(Scenario s,Solution solution) {
        List<String> errors=new ArrayList<>();
        if(solution==null)return new Validation(false,List.of("Missing solution"));
        ScenarioData d=new ScenarioData(s);
        Set<Triple> expectedX=new HashSet<>();
        for(String p:d.products)for(String c:d.cells)for(String t:d.shifts)expectedX.add(new Triple(p,c,t));
        Map<Triple,Integer> x=new HashMap<>();
        for(Production r:solution.production()) {
            if(x.put(new Triple(r.productId(),r.cellId(),r.shiftId()),r.units())!=null) errors.add("Duplicate production key");
            if(r.units()<0)errors.add("Negative production");
        }
        if(!x.keySet().equals(expectedX)) errors.add("Missing or unknown production keys");
        Map<Pair,Integer> y=new HashMap<>();
        for(Activity r:solution.activity()) {
            if(y.put(new Pair(r.cellId(),r.shiftId()),r.active())!=null)errors.add("Duplicate activity key");
            if(r.active()!=0&&r.active()!=1)errors.add("Activity must be binary");
        }
        if(!y.keySet().equals(Inputs.pairs(Set.copyOf(d.cells),Set.copyOf(d.shifts))))errors.add("Missing or unknown activity keys");
        Map<Pair,Stock> stocks=new HashMap<>();
        for(Stock r:solution.stock()) {
            if(stocks.put(new Pair(r.productId(),r.shiftId()),r)!=null)errors.add("Duplicate stock key");
            if(r.inventoryUnits()<0||r.backlogUnits()<0)errors.add("Negative stock");
        }
        if(!stocks.keySet().equals(Inputs.pairs(Set.copyOf(d.products),Set.copyOf(d.shifts))))errors.add("Missing or unknown stock keys");
        if(!errors.isEmpty())return new Validation(false,errors);
        for(String p:d.products) {
            long made=x.entrySet().stream().filter(e->e.getKey().product().equals(p)).mapToLong(Map.Entry::getValue).sum();
            if(made>d.useful.get(p))errors.add(p+": useful-production limit exceeded");
        }
        for(var e:x.entrySet())if(e.getValue()>0&&d.rate(e.getKey().product(),e.getKey().cell())==0)errors.add("Ineligible production");
        for(String c:d.cells)for(String t:d.shifts) {
            long used=0; boolean output=false;
            for(String p:d.products) { int q=x.get(new Triple(p,c,t)); used+=(long)q*d.rate(p,c); output|=q>0; }
            if(used>d.capacity(c,t))errors.add(c+"/"+t+": machine capacity exceeded");
            if(y.get(new Pair(c,t))!=(output?1:0))errors.add(c+"/"+t+": activity/output mismatch");
        }
        for(Shift t:s.shifts()) {
            int staff=0; for(String c:d.cells)staff+=d.crews.get(c)*y.get(new Pair(c,t.id()));
            if(staff>t.availableOperators())errors.add(t.id()+": staffing exceeded");
        }
        // Recompute from raw output, retaining the provided states for comparison.
        // long arithmetic above ensures corrupt int quantities cannot overflow capacity checks.
        if(x.values().stream().anyMatch(q->q>200000))errors.add("Production exceeds maximum possible demand");
        if(!errors.isEmpty())return new Validation(false,errors);
        Solution truth=make(s,x);
        for(Stock r:truth.stock())if(!r.equals(stocks.get(new Pair(r.productId(),r.shiftId()))))errors.add(r.productId()+"/"+r.shiftId()+": stock balance or exclusivity violated");
        if(solution.objective()!=truth.objective())errors.add("Reported objective differs from recomputed backlog");
        if(!Objects.equals(solution.metrics(),truth.metrics()))errors.add("Reported metrics differ from recomputation");
        return new Validation(errors.isEmpty(),errors);
    }
}
