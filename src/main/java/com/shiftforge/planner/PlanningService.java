package com.shiftforge.planner;

import java.util.*;
import static com.shiftforge.planner.Domain.*;

public final class PlanningService {
    @FunctionalInterface public interface Optimizer { Result solve(Scenario scenario,int seconds); }
    private final Optimizer optimizer;
    public PlanningService() { this(GurobiOptimizer::solve); }
    public PlanningService(Optimizer optimizer) { this.optimizer=Objects.requireNonNull(optimizer); }
    public Result solve(SolveRequest request) {
        Inputs.require(request.solverTimeLimitSeconds()>=1&&request.solverTimeLimitSeconds()<=60,"Time limit must be 1..60 seconds");
        if("greedy".equals(request.algorithm())) {
            Solution plan=GreedyPlanner.plan(request.scenario());
            Validation check=Plans.check(request.scenario(),plan);
            return result(request.scenario(),"greedy",check.valid()?"heuristic_feasible":"invalid_result",Map.of(),null,
                check.valid()?plan:null,check,check.valid()?null:"Greedy plan failed independent validation");
        }
        Inputs.require("gurobi".equals(request.algorithm()),"Algorithm must be gurobi or greedy");
        return optimizer.solve(request.scenario(),request.solverTimeLimitSeconds());
    }
    static Map<String,Number> settings(int seconds) {
        Map<String,Number> m=new LinkedHashMap<>();
        m.put("TimeLimit",seconds);m.put("MIPGap",0.0);m.put("MIPGapAbs",0.0);m.put("Threads",1);m.put("Seed",0);m.put("FeasibilityTol",1e-7);m.put("IntFeasTol",1e-6);
        return m;
    }
    static Result result(Scenario s,String algorithm,String status,Map<String,Number> settings,SolverInfo info,Solution plan,Validation check,String error) {
        return new Result(1,s,algorithm,status,settings,info,plan,check,error);
    }
    /** Testable interpretation of solver termination, separate from native execution. */
    public static Result assess(Scenario s,Map<String,Number> settings,SolverInfo info,Solution candidate) {
        String error=null,status;
        Validation validation=null;
        if(Set.of("INFEASIBLE","INF_OR_UNBD","UNBOUNDED","NUMERIC").contains(info.termination())) {
            status="solver_error";error="Solver rejected a model with a known zero-production feasible plan";
        } else if(candidate==null) {
            status="OPTIMAL".equals(info.termination())?"solver_error":"no_solution";
            if("solver_error".equals(status))error="Optimal termination without a candidate";
        } else {
            validation=Plans.check(s,candidate);
            if(!validation.valid()) { status="invalid_result";error="Solver output failed independent validation"; }
            else if(info.rawObjective()==null || !Double.isFinite(info.rawObjective()) || Math.abs(info.rawObjective()-candidate.objective())>1e-5
                || (info.objectiveBound()!=null&&(!Double.isFinite(info.objectiveBound())||info.objectiveBound()>candidate.objective()+1e-6))) {
                status="solver_error";error="Solver objective/bound disagrees with independently checked output";
            } else if("OPTIMAL".equals(info.termination())) {
                if(settings.getOrDefault("MIPGap",1).doubleValue()!=0 || settings.getOrDefault("MIPGapAbs",1).doubleValue()!=0
                    || info.objectiveBound()==null || Math.abs(info.objectiveBound()-candidate.objective())>1e-5) {
                    status="solver_error";error="Optimality settings or bound are inconsistent";
                } else status="optimal";
            } else status="feasible";
        }
        return result(s,"gurobi",status,settings,info,("optimal".equals(status)||"feasible".equals(status))?candidate:null,validation,error);
    }
}
