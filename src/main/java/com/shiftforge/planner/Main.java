package com.shiftforge.planner;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.shiftforge.planner.Domain.*;

/** CLI entry point; HTTP and CLI share the same domain service. */
public final class Main {
    private Main(){}
    public static void main(String[] args)throws Exception {
        try { int code=run(args);if(code!=0)System.exit(code); }
        catch(IllegalArgumentException e){System.err.println(e.getMessage());System.exit(2);}
    }
    static int run(String[] args)throws Exception {
        if(args.length==0||"help".equals(args[0])||"--help".equals(args[0])) {
            System.out.println("ShiftForge\n  serve [--host 127.0.0.1] [--port 8012] [--ui frontend/dist]\n  solve (--example base|tiny|lookahead | --input FILE) [--algorithm gurobi|greedy] [--time-limit 10] [--outage-cell C3 --outage-start 5 --outage-end 6] [--output FILE]\n  example base|tiny|lookahead [--output FILE]\n  validate FILE\n  check RESULT_FILE");return 0;
        }
        String command=args[0];
        if("healthcheck".equals(command)) {
            var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8012/api/health"))
                .timeout(java.time.Duration.ofSeconds(2)).build(),java.net.http.HttpResponse.BodyHandlers.discarding());
            return response.statusCode()==200?0:1;
        }
        if("validate".equals(command)) {
            Inputs.require(args.length==2,"validate requires one input file");
            Inputs.scenario(Json.tree(Files.readString(Path.of(args[1]))));System.out.println("Scenario is valid");return 0;
        }
        if("check".equals(command)) {
            Inputs.require(args.length==2,"check requires one result file");
            var tree=Json.tree(Files.readString(Path.of(args[1])));
            Scenario s=Inputs.scenario(tree.get("input"));Result r=Json.convert(tree,Result.class);
            Validation validation=Plans.check(s,r.solution());System.out.print(Json.write(validation));return validation.valid()?0:1;
        }
        String example="example".equals(command)&&args.length>1?args[1]:null;
        int offset=example==null?1:2;
        Map<String,String> options=options(args,offset);
        switch(command) {
            case "serve" -> {
                allowed(options,"host","port","ui");
                int port=parseInt(options.getOrDefault("port","8012"),"port");Inputs.require(port>=0&&port<=65535,"port out of range");
                String host=options.getOrDefault("host","127.0.0.1");
                HistoryStore history=DatabaseConfig.fromEnvironment().map(HistoryStore::new).orElse(null);
                if(history!=null)history.migrate();
                PlannerServer server=new PlannerServer(host,port,options.containsKey("ui")?Path.of(options.get("ui")):null,new PlanningService(),history);
                Runtime.getRuntime().addShutdownHook(new Thread(server::close));server.start();
                System.out.println("ShiftForge listening on http://"+host+":"+server.port());
                new java.util.concurrent.CountDownLatch(1).await();return 0;
            }
            case "example" -> {
                allowed(options,"output");Inputs.require(example!=null,"example needs an example ID");output(Examples.load(example),options.get("output"));return 0;
            }
            case "solve" -> {
                allowed(options,"example","input","algorithm","time-limit","outage-cell","outage-start","outage-end","output");
                Inputs.require(options.containsKey("example")^options.containsKey("input"),"Choose exactly one of --example and --input");
                Scenario s=options.containsKey("example")?Examples.load(options.get("example")):Inputs.scenario(Json.tree(Files.readString(Path.of(options.get("input")))));
                if(options.containsKey("outage-cell")||options.containsKey("outage-start")||options.containsKey("outage-end")) {
                    Inputs.require(options.keySet().containsAll(Set.of("outage-cell","outage-start","outage-end")),"All three outage options are required");
                    s=Examples.outage(s,options.get("outage-cell"),parseInt(options.get("outage-start"),"outage start"),parseInt(options.get("outage-end"),"outage end"));
                }
                int seconds=parseInt(options.getOrDefault("time-limit","10"),"time limit");
                Result result=new PlanningService().solve(new SolveRequest(s,options.getOrDefault("algorithm","gurobi"),seconds));
                output(result,options.get("output"));return Set.of("optimal","feasible","heuristic_feasible").contains(result.status())?0:1;
            }
            default -> throw new IllegalArgumentException("Unknown command: "+command);
        }
    }
    private static Map<String,String> options(String[] args,int offset) {
        Map<String,String> out=new HashMap<>();
        for(int i=offset;i<args.length;i+=2) {
            Inputs.require(args[i].startsWith("--")&&i+1<args.length,"Options require --name value pairs");
            Inputs.require(out.put(args[i].substring(2),args[i+1])==null,"Duplicate option: "+args[i]);
        }
        return out;
    }
    private static void allowed(Map<String,String> options,String...keys){Set<String> allowed=Set.of(keys);options.keySet().forEach(k->Inputs.require(allowed.contains(k),"Unknown option: --"+k));}
    private static int parseInt(String value,String label){try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException(label+" must be an integer");}}
    private static void output(Object value,String filename)throws IOException {
        String json=Json.write(value);
        if(filename==null)System.out.print(json);
        else {Path p=Path.of(filename);if(p.toAbsolutePath().getParent()!=null)Files.createDirectories(p.toAbsolutePath().getParent());Files.writeString(p,json);}
    }
}
