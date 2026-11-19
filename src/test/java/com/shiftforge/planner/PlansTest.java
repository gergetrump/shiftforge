package com.shiftforge.planner;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiftforge.planner.Domain.*;

class PlansTest {
    static Result reference(String name)throws Exception {
        try(var in=PlansTest.class.getResourceAsStream("/reference/"+name+".json")) {
            return Json.convert(Json.tree(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)),Result.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"base","tiny","lookahead","short_outage","full_outage"})
    void greedyMatchesOriginalPythonExports(String name)throws Exception {
        Result expected=reference(name);
        PlanningService service=new PlanningService((s,t)->{throw new AssertionError("Greedy must not initialize Gurobi");});
        Result result=service.solve(new SolveRequest(expected.input(),"greedy",10));
        assertEquals(expected,result);assertTrue(result.validation().valid());
        assertEquals(result,Json.convert(Json.tree(Json.write(result)),Result.class));
    }
    @ParameterizedTest @ValueSource(strings={"capacity","staff","activation","stock","keys","objective","metrics","negative","eligibility","extraProduction"})
    void checkerRejectsCorruptOutput(String corrupt) {
        Scenario s=Examples.load("tiny");Solution original=GreedyPlanner.plan(s);
        ObjectNode json=Json.MAPPER.valueToTree(original);
        switch(corrupt) {
            case "capacity"->find(json,"production","product_id","A","cell_id","C1","shift_id","T1").put("units",3);
            case "staff"->{find(json,"production","product_id","B","cell_id","C2","shift_id","T1").put("units",1);find(json,"activity","cell_id","C2","shift_id","T1").put("active",1);}
            case "activation"->((ObjectNode)json.withArray("activity").get(0)).put("active",0);
            case "stock"->{((ObjectNode)json.withArray("stock").get(0)).put("inventory_units",1).put("backlog_units",1);}
            case "keys"->json.withArray("production").remove(0);
            case "objective"->json.put("objective",99);
            case "metrics"->((ObjectNode)json.get("metrics")).put("capacity_utilization",0.999);
            case "negative"->((ObjectNode)json.withArray("production").get(0)).put("units",-1);
            case "eligibility"->find(json,"production","product_id","A","cell_id","C2","shift_id","T1").put("units",1);
            case "extraProduction"->find(json,"production","product_id","A","cell_id","C1","shift_id","T1").put("units",Integer.MAX_VALUE);
        }
        assertFalse(Plans.check(s,Json.convert(json,Solution.class)).valid());
    }
    @Test void zeroProductionIsFeasibleAndHasBacklog() {
        Scenario s=Examples.load("tiny");Solution zero=Plans.make(s,Map.of());
        assertEquals(6,zero.objective());assertTrue(Plans.check(s,zero).valid());
    }
    @Test void rejectsFractionalResultQuantity() {
        ObjectNode json=Json.MAPPER.valueToTree(GreedyPlanner.plan(Examples.load("tiny")));
        ((ObjectNode)json.withArray("production").get(0)).put("units",1.5);
        assertThrows(IllegalArgumentException.class,()->Json.convert(json,Solution.class));
    }
    private static ObjectNode find(ObjectNode json,String list,String...pairs) {
        for(var row:json.withArray(list)) {
            boolean match=true;for(int i=0;i<pairs.length;i+=2)match&=row.path(pairs[i]).asText().equals(pairs[i+1]);
            if(match)return (ObjectNode)row;
        }
        throw new AssertionError("Missing fixture row");
    }
}
