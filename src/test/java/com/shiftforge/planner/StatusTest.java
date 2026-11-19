package com.shiftforge.planner;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiftforge.planner.Domain.*;

class StatusTest {
    @ParameterizedTest @CsvSource({"OPTIMAL,true,optimal","TIME_LIMIT,true,feasible","TIME_LIMIT,false,no_solution","INFEASIBLE,false,solver_error","NUMERIC,true,solver_error","OPTIMAL,false,solver_error"})
    void interpretsTerminationWithoutInventingOptimality(String termination,boolean hasCandidate,String expected) {
        Scenario s=Examples.load("tiny");Solution candidate=hasCandidate?Plans.make(s,Map.of()):null;
        SolverInfo info=new SolverInfo("fake",termination,2,hasCandidate?6.0:null,"OPTIMAL".equals(termination)?6.0:0.0,null,0.01);
        Result result=PlanningService.assess(s,PlanningService.settings(10),info,candidate);
        assertEquals(expected,result.status());
    }
    @Test void rejectsFalseOptimalBoundAndNonzeroGap() {
        Scenario s=Examples.load("tiny");Solution plan=Plans.make(s,Map.of());
        SolverInfo badBound=new SolverInfo("fake","OPTIMAL",2,6.0,0.0,null,0.01);
        assertEquals("solver_error",PlanningService.assess(s,PlanningService.settings(10),badBound,plan).status());
        Map<String,Number> relaxed=new HashMap<>(PlanningService.settings(10));relaxed.put("MIPGap",0.1);
        SolverInfo valid=new SolverInfo("fake","OPTIMAL",2,6.0,6.0,null,0.01);
        assertEquals("solver_error",PlanningService.assess(s,relaxed,valid,plan).status());
    }
    @Test void preservesUnavailableSolverWithoutGreedyFallback() {
        PlanningService service=new PlanningService((s,t)->PlanningService.result(s,"gurobi","solver_unavailable",PlanningService.settings(t),null,null,null,"No test license"));
        Result result=service.solve(new SolveRequest(Examples.load("tiny"),"gurobi",10));
        assertEquals("solver_unavailable",result.status());assertEquals("gurobi",result.algorithm());assertNull(result.solution());
    }
}
