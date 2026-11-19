package com.shiftforge.planner;

import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiftforge.planner.Domain.*;

/** Opt-in native tests; no fake solver result is used as evidence of a real solve. */
@Tag("gurobi")
class GurobiTest {
    private Result optimize(Scenario s) {
        Result result=new PlanningService().solve(new SolveRequest(s,"gurobi",10));
        if(Boolean.getBoolean("requireGurobi"))assertNotEquals("solver_unavailable",result.status(),result.error());
        Assumptions.assumeFalse("solver_unavailable".equals(result.status()),"Java-capable Gurobi license unavailable");
        assertEquals("optimal",result.status(),result.error());assertTrue(result.validation().valid());return result;
    }
    @ParameterizedTest @ValueSource(strings={"base","tiny","lookahead","short_outage","full_outage"})
    void solvesReferenceCases(String name)throws Exception {
        Scenario s=PlansTest.reference(name).input();Result result=optimize(s);
        assertEquals("full_outage".equals(name)?550:0,result.solution().objective());
    }
    @Test void staffingShortageMatchesIndependentTinyEnumeration() {
        ObjectNode data=Json.MAPPER.valueToTree(Examples.load("tiny"));
        ((ObjectNode)data.withArray("shifts").get(1)).put("available_operators",0);
        Scenario s=Inputs.scenario(data);assertEquals(enumerateTiny(s),optimize(s).solution().objective());
    }
    @Test void sharedCellReservesCrewOnce() {
        String json="""
          {"schema_version":1,"name":"Shared cell","data_origin":"synthetic",
           "products":[{"id":"A","initial_inventory_units":0},{"id":"B","initial_inventory_units":0}],
           "cells":[{"id":"C1","crew_operators":1}],
           "processing_times":[{"product_id":"A","cell_id":"C1","minutes_per_unit":1},{"product_id":"B","cell_id":"C1","minutes_per_unit":1}],
           "shifts":[{"id":"T1","index":1,"duration_minutes":2,"available_operators":1}],
           "capacity":[{"cell_id":"C1","shift_id":"T1","available_machine_minutes":2}],
           "demand":[{"product_id":"A","shift_id":"T1","due_units":1},{"product_id":"B","shift_id":"T1","due_units":1}]}
          """;
        Result r=optimize(Inputs.scenario(Json.tree(json)));assertEquals(0,r.solution().objective());assertEquals(1,r.solution().metrics().staffing().getFirst().reservedOperators());
    }
    @Test void initialStockAndNoEligibleCellsAreHandled() {
        ObjectNode data=Json.MAPPER.valueToTree(Examples.load("lookahead"));
        ((ObjectNode)data.withArray("products").get(0)).put("initial_inventory_units",5);
        Result r=optimize(Inputs.scenario(data));assertEquals(0,r.solution().metrics().producedUnits());assertEquals(9,r.solution().metrics().inventoryUnitShifts());
        data=Json.MAPPER.valueToTree(Examples.load("lookahead"));data.withArray("processing_times").removeAll();
        assertEquals(1,optimize(Inputs.scenario(data)).solution().objective());
    }
    // Physical enumeration of the tiny two-cell case, not a transcription of the MILP.
    private long enumerateTiny(Scenario s) {
        int[][] choices={{0,0},{1,0},{2,0},{0,1},{0,2}};long best=Long.MAX_VALUE;
        for(int[] a:choices)for(int[] b:choices) {
            int[][] seq={a,b};boolean valid=true;int[] net={0,0};long score=0;
            for(int i=0;i<2;i++) {
                Shift t=s.orderedShifts().get(i);int active=(seq[i][0]>0?1:0)+(seq[i][1]>0?1:0);
                if(active>t.availableOperators())valid=false;
                for(int p=0;p<2;p++) {String cell="C"+(p+1),product=p==0?"A":"B";
                    int cap=s.capacity().stream().filter(c->c.cellId().equals(cell)&&c.shiftId().equals(t.id())).findFirst().orElseThrow().availableMachineMinutes();
                    if(seq[i][p]>cap)valid=false;
                    int due=s.demand().stream().filter(d->d.productId().equals(product)&&d.shiftId().equals(t.id())).findFirst().orElseThrow().dueUnits();
                    net[p]+=seq[i][p]-due;score+=Math.max(-net[p],0);
                }
            }
            for(int p=0;p<2;p++){String product=p==0?"A":"B";int total=s.demand().stream().filter(d->d.productId().equals(product)).mapToInt(Demand::dueUnits).sum();if(a[p]+b[p]>total)valid=false;}
            if(valid)best=Math.min(best,score);
        }
        return best;
    }
}
