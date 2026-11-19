package com.shiftforge.planner;

import com.gurobi.gurobi.*;
import java.util.*;
import static com.shiftforge.planner.Domain.*;

/** Native Java MILP construction. No Python interpreter, subprocess, or fallback solve. */
final class GurobiOptimizer {
    private GurobiOptimizer() {}
    static Result solve(Scenario s,int seconds) {
        Map<String,Number> settings=PlanningService.settings(seconds);
        GRBEnv env=null; GRBModel model=null; boolean initialized=false;
        try {
            env=new GRBEnv(true);env.set(GRB.IntParam.OutputFlag,0);env.start();initialized=true;
            model=new GRBModel(env);model.set(GRB.StringAttr.ModelName,"shiftforge_capacity");
            model.set(GRB.DoubleParam.TimeLimit,seconds);model.set(GRB.DoubleParam.MIPGap,0);model.set(GRB.DoubleParam.MIPGapAbs,0);
            model.set(GRB.IntParam.Threads,1);model.set(GRB.IntParam.Seed,0);
            model.set(GRB.DoubleParam.FeasibilityTol,1e-7);model.set(GRB.DoubleParam.IntFeasTol,1e-6);
            ScenarioData d=new ScenarioData(s);
            Map<Triple,GRBVar> x=new LinkedHashMap<>();
            Map<Pair,GRBVar> y=new LinkedHashMap<>(),inv=new LinkedHashMap<>(),back=new LinkedHashMap<>(),sign=new LinkedHashMap<>();
            Map<Triple,Integer> upper=new HashMap<>();
            for(String p:d.products)for(String c:d.cells)if(d.rate(p,c)>0)for(String t:d.shifts) {
                Triple k=new Triple(p,c,t);int ub=Math.min(d.useful.get(p),d.capacity(c,t)/d.rate(p,c));upper.put(k,ub);
                x.put(k,model.addVar(0,ub,0,GRB.INTEGER,"x["+p+","+c+","+t+"]"));
            }
            for(String c:d.cells)for(String t:d.shifts) y.put(new Pair(c,t),model.addVar(0,1,0,GRB.BINARY,"y["+c+","+t+"]"));
            for(String p:d.products) {
                int cumulative=0;
                for(String t:d.shifts) {
                    Pair k=new Pair(p,t);cumulative+=d.demand(p,t);
                    inv.put(k,model.addVar(0,d.initial.get(p)+d.useful.get(p),0,GRB.INTEGER,"I["+p+","+t+"]"));
                    back.put(k,model.addVar(0,cumulative,0,GRB.INTEGER,"B["+p+","+t+"]"));
                    sign.put(k,model.addVar(0,1,0,GRB.BINARY,"sign["+p+","+t+"]"));
                }
            }
            for(String c:d.cells)for(String t:d.shifts) {
                Pair ct=new Pair(c,t); GRBLinExpr capacity=new GRBLinExpr(),nonempty=new GRBLinExpr();
                for(String p:d.products)if(d.rate(p,c)>0) {
                    Triple k=new Triple(p,c,t);capacity.addTerm(d.rate(p,c),x.get(k));nonempty.addTerm(1,x.get(k));
                    GRBLinExpr activation=new GRBLinExpr();activation.addTerm(1,x.get(k));activation.addTerm(-upper.get(k),y.get(ct));
                    model.addConstr(activation,GRB.LESS_EQUAL,0,"activation["+p+","+c+","+t+"]");
                }
                capacity.addTerm(-d.capacity(c,t),y.get(ct));model.addConstr(capacity,GRB.LESS_EQUAL,0,"capacity["+c+","+t+"]");
                nonempty.addTerm(-1,y.get(ct));model.addConstr(nonempty,GRB.GREATER_EQUAL,0,"nonempty["+c+","+t+"]");
            }
            for(Shift t:s.orderedShifts()) {
                GRBLinExpr staff=new GRBLinExpr();for(String c:d.cells)staff.addTerm(d.crews.get(c),y.get(new Pair(c,t.id())));
                model.addConstr(staff,GRB.LESS_EQUAL,t.availableOperators(),"staff["+t.id()+"]");
            }
            GRBLinExpr objective=new GRBLinExpr();
            for(String p:d.products) {
                GRBLinExpr useful=new GRBLinExpr();
                for(String c:d.cells)if(d.rate(p,c)>0)for(String t:d.shifts)useful.addTerm(1,x.get(new Triple(p,c,t)));
                model.addConstr(useful,GRB.LESS_EQUAL,d.useful.get(p),"useful["+p+"]");
                int cumulative=0;
                for(int i=0;i<d.shifts.size();i++) {
                    String t=d.shifts.get(i);Pair pt=new Pair(p,t);cumulative+=d.demand(p,t);
                    GRBLinExpr balance=new GRBLinExpr();balance.addTerm(1,inv.get(pt));balance.addTerm(-1,back.get(pt));
                    if(i>0) { Pair prev=new Pair(p,d.shifts.get(i-1));balance.addTerm(-1,inv.get(prev));balance.addTerm(1,back.get(prev)); }
                    for(String c:d.cells)if(d.rate(p,c)>0)balance.addTerm(-1,x.get(new Triple(p,c,t)));
                    model.addConstr(balance,GRB.EQUAL,(i==0?d.initial.get(p):0)-d.demand(p,t),"balance["+p+","+t+"]");
                    GRBLinExpr isign=new GRBLinExpr();isign.addTerm(1,inv.get(pt));isign.addTerm(-(d.initial.get(p)+d.useful.get(p)),sign.get(pt));
                    model.addConstr(isign,GRB.LESS_EQUAL,0,"inventory_sign["+p+","+t+"]");
                    GRBLinExpr bsign=new GRBLinExpr();bsign.addTerm(1,back.get(pt));bsign.addTerm(cumulative,sign.get(pt));
                    model.addConstr(bsign,GRB.LESS_EQUAL,cumulative,"backlog_sign["+p+","+t+"]");
                    objective.addTerm(1,back.get(pt));
                }
            }
            model.setObjective(objective,GRB.MINIMIZE);model.optimize();
            int code=model.get(GRB.IntAttr.Status);boolean hasSolution=model.get(GRB.IntAttr.SolCount)>0;
            Double bound;
            try { bound=finite(model.get(GRB.DoubleAttr.ObjBound)); }catch(GRBException e){bound=null;}
            SolverInfo info=new SolverInfo(GRB.VERSION_MAJOR+"."+GRB.VERSION_MINOR+"."+GRB.VERSION_TECHNICAL,statusName(code),code,
                hasSolution?finite(model.get(GRB.DoubleAttr.ObjVal)):null,bound,
                hasSolution?finite(model.get(GRB.DoubleAttr.MIPGap)):null,model.get(GRB.DoubleAttr.Runtime));
            Solution candidate=null;
            if(hasSolution) {
                Map<Triple,Integer> output=new HashMap<>();Map<Pair,Integer> activity=new HashMap<>();Map<Pair,Stock> stock=new HashMap<>();
                try {
                    for(var e:x.entrySet())output.put(e.getKey(),integer(e.getValue().get(GRB.DoubleAttr.X)));
                    for(var e:y.entrySet())activity.put(e.getKey(),integer(e.getValue().get(GRB.DoubleAttr.X)));
                    for(var e:inv.entrySet()) { Pair k=e.getKey();stock.put(k,new Stock(k.first(),k.second(),integer(e.getValue().get(GRB.DoubleAttr.X)),integer(back.get(k).get(GRB.DoubleAttr.X)))); }
                }catch(IllegalArgumentException e) {
                    return PlanningService.result(s,"gurobi","invalid_result",settings,info,null,null,"Solver returned invalid integer decisions");
                }
                candidate=Plans.make(s,output,activity,stock);
            }
            return PlanningService.assess(s,settings,info,candidate);
        }catch(GRBException e) {
            String status=!initialized||e.getErrorCode()==10009?"solver_unavailable":"solver_error";
            return PlanningService.result(s,"gurobi",status,settings,null,null,null,"Gurobi error "+e.getErrorCode()+"; check local installation, license and model limits. Greedy is available separately.");
        }catch(UnsatisfiedLinkError|NoClassDefFoundError e) {
            return PlanningService.result(s,"gurobi","solver_unavailable",settings,null,null,null,"Gurobi native libraries are unavailable on this platform. Greedy is available separately.");
        }finally {
            if(model!=null)model.dispose();
            if(env!=null)try{env.dispose();}catch(GRBException ignored){/* Do not overwrite the recorded solve status during cleanup. */}
        }
    }
    private static int integer(double value) {
        if(!Double.isFinite(value)||Math.rint(value)<0||Math.rint(value)>Integer.MAX_VALUE||Math.abs(value-Math.rint(value))>1e-5)throw new IllegalArgumentException("Not a nonnegative integer decision");
        return (int)Math.rint(value);
    }
    private static Double finite(double value) { return Double.isFinite(value)?value:null; }
    private static String statusName(int code) {
        return switch(code) {
            case 2->"OPTIMAL";case 3->"INFEASIBLE";case 4->"INF_OR_UNBD";case 5->"UNBOUNDED";case 7->"ITERATION_LIMIT";
            case 8->"NODE_LIMIT";case 9->"TIME_LIMIT";case 10->"SOLUTION_LIMIT";case 11->"INTERRUPTED";case 12->"NUMERIC";
            case 13->"SUBOPTIMAL";case 15->"USER_OBJ_LIMIT";case 16->"WORK_LIMIT";case 17->"MEM_LIMIT";default->"STATUS_"+code;
        };
    }
}
