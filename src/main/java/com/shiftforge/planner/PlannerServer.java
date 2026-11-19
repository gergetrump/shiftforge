package com.shiftforge.planner;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import spark.Request;
import spark.Response;
import spark.Service;
import static com.shiftforge.planner.Domain.*;

/** SparkJava transport; CLI and HTTP continue sharing the same planning implementation. */
public final class PlannerServer implements AutoCloseable {
    private static final int MAX_BODY = 1_048_576;
    private final Service api = Service.ignite();
    private final PlanningService planning;
    private final HistoryStore history;
    private final ReentrantLock solveLock = new ReentrantLock();
    private final Path ui;
    private final int port;
    public PlannerServer(String host,int port,Path ui,PlanningService planning) throws IOException {
        this(host,port,ui,planning,null);
    }
    public PlannerServer(String host,int requestedPort,Path ui,PlanningService planning,HistoryStore history) throws IOException {
        this.planning=planning;this.history=history;this.ui=ui==null?null:ui.toRealPath();
        // Spark exposes the configured port, so choose a free local port for isolated tests requesting 0.
        if(requestedPort==0) {try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getByName(host))){port=socket.getLocalPort();}}
        else port=requestedPort;
        api.ipAddress(host);api.port(port);api.threadPool(16,2,30_000);
    }
    public void start() {
        api.initExceptionHandler(e->{throw new IllegalStateException("HTTP server could not start",e);});
        api.before((request,response)-> {
            response.header("X-Content-Type-Options","nosniff");
            if(request.pathInfo().startsWith("/api/"))response.type("application/json; charset=utf-8");
            String origin=request.headers("Origin");
            if("http://127.0.0.1:5173".equals(origin)||"http://localhost:5173".equals(origin)) {
                response.header("Access-Control-Allow-Origin",origin);response.header("Vary","Origin");
                response.header("Access-Control-Allow-Methods","GET, POST, OPTIONS");response.header("Access-Control-Allow-Headers","Content-Type");
            }
        });
        api.options("/*",(request,response)->{response.status(204);return "";});
        api.exception(IllegalArgumentException.class,(e,request,response)->error(response,422,e.getMessage()));
        api.exception(HistoryStore.NotFound.class,(e,request,response)->error(response,404,"Saved item not found"));
        api.exception(HistoryStore.Unavailable.class,(e,request,response)->error(response,503,"History storage is unavailable; nothing was reported as saved"));
        api.exception(HttpFailure.class,(e,request,response)->error(response,e.status,e.getMessage()));
        api.exception(Exception.class,(e,request,response)->error(response,500,"Internal planning service error"));
        api.get("/api/health",(request,response)->Json.write(Map.of("status","ok","service","shiftforge","history_enabled",history!=null)));
        api.get("/api/examples",(request,response)->Json.write(Examples.IDS.stream().map(id->Map.of("id",id,"scenario",Examples.load(id))).toList()));
        api.post("/api/solve",(request,response)-> {
            Result result=solve(Inputs.request(body(request)));
            response.status(resultStatus(result));return Json.write(result);
        });
        api.post("/api/scenarios",(request,response)-> {
            var scenario=Inputs.scenario(body(request));
            var saved=store().createScenario(scenario);
            response.status(201);response.header("Location","/api/scenarios/"+saved.id());return Json.write(saved);
        });
        api.get("/api/scenarios",(request,response)->Json.write(store().scenarios(page(request,"limit",20),page(request,"offset",0))));
        api.get("/api/scenarios/:id",(request,response)->Json.write(store().scenario(uuid(request.params("id")))));
        api.post("/api/runs",(request,response)-> {
            var json=body(request);
            Inputs.require(json!=null&&json.isObject(),"Run request must be an object");
            Set<String> keys=new HashSet<>();json.fieldNames().forEachRemaining(keys::add);
            Inputs.require(keys.contains("scenario_id")&&Set.of("scenario_id","algorithm","solver_time_limit_seconds").containsAll(keys),"Run request has missing or unknown fields");
            Inputs.require(json.path("scenario_id").isTextual(),"scenario_id must be a UUID string");
            HistoryStore store=store();var saved=store.scenario(uuid(json.path("scenario_id").textValue()));
            var payload=Json.MAPPER.createObjectNode();payload.set("scenario",Json.MAPPER.valueToTree(saved.scenario()));
            if(json.has("algorithm"))payload.set("algorithm",json.get("algorithm"));
            if(json.has("solver_time_limit_seconds"))payload.set("solver_time_limit_seconds",json.get("solver_time_limit_seconds"));
            // Result is produced by the service; caller-supplied results are never accepted.
            var run=store.createRun(saved,solve(Inputs.request(payload)));
            response.status(201);response.header("Location","/api/runs/"+run.id());return Json.write(run);
        });
        api.get("/api/runs",(request,response)->Json.write(store().runs(page(request,"limit",20),page(request,"offset",0))));
        api.get("/api/runs/:id",(request,response)->Json.write(store().run(uuid(request.params("id")))));
        api.get("/*",(request,response)->staticFile(request,response));
        api.notFound((request,response)->{response.type("application/json");return Json.write(Map.of("detail","Not found"));});
        api.internalServerError((request,response)->{response.type("application/json");return Json.write(Map.of("detail","Internal planning service error"));});
        api.awaitInitialization();
    }
    private HistoryStore store() {if(history==null)throw new HttpFailure(503,"History is disabled; configure PostgreSQL to save or reopen items");return history;}
    private Result solve(SolveRequest request) {
        if(!solveLock.tryLock())throw new HttpFailure(409,"A planning request is running in this API process; retry when it finishes.");
        try{return planning.solve(request);}finally{solveLock.unlock();}
    }
    private static int resultStatus(Result result) {return switch(result.status()){case "solver_unavailable"->503;case "solver_error","invalid_result"->500;default->200;};}
    private static com.fasterxml.jackson.databind.JsonNode body(Request request) throws IOException {
        long length=request.raw().getContentLengthLong();
        if(length>MAX_BODY)throw new HttpFailure(413,"Request body exceeds 1 MiB");
        byte[] bytes=request.raw().getInputStream().readNBytes(MAX_BODY+1);
        if(bytes.length>MAX_BODY)throw new HttpFailure(413,"Request body exceeds 1 MiB");
        return Json.tree(new String(bytes,StandardCharsets.UTF_8));
    }
    private static int page(Request request,String key,int fallback) {
        String value=request.queryParams(key);if(value==null)return fallback;
        Inputs.require(request.queryParamsValues(key).length==1&&value.matches("[0-9]+"),"Invalid pagination");
        try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid pagination");}
    }
    private static UUID uuid(String value) {
        Inputs.require(value!=null&&value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"Invalid UUID");
        return UUID.fromString(value);
    }
    private Object staticFile(Request request,Response response) throws IOException {
        if(ui==null||request.pathInfo().startsWith("/api/"))throw new HttpFailure(404,"Not found");
        Path path=ui.resolve(request.pathInfo().equals("/")?"index.html":request.pathInfo().substring(1)).normalize();
        if(!path.startsWith(ui)||!Files.isRegularFile(path))throw new HttpFailure(404,"Not found");
        // Do not use Spark's external static handler: canonical roots also reject symlink escapes.
        Path real=path.toRealPath();if(!real.startsWith(ui))throw new HttpFailure(404,"Not found");
        String name=real.getFileName().toString();
        response.type(name.endsWith(".html")?"text/html; charset=utf-8":name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":name.endsWith(".svg")?"image/svg+xml":"application/octet-stream");
        response.status(200);try(OutputStream out=response.raw().getOutputStream()){Files.copy(real,out);}return null;
    }
    private static void error(Response response,int status,String message) {response.status(status);response.type("application/json; charset=utf-8");response.body(Json.write(Map.of("detail",message==null?"Invalid request":message)));}
    private static final class HttpFailure extends RuntimeException {final int status;HttpFailure(int status,String message){super(message);this.status=status;}}
    public int port(){return port;}
    @Override public void close(){api.stop();api.awaitStop();}
}
