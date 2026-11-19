package com.shiftforge.planner;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static com.shiftforge.planner.Domain.*;

/** Local synchronous API; one nonblocking optimization slot per server process. */
public final class PlannerServer implements AutoCloseable {
    private static final int MAX_BODY=1_048_576;
    private final HttpServer server;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final PlanningService service;
    private final ReentrantLock solveLock=new ReentrantLock();
    private final Path ui;
    public PlannerServer(String host,int port,Path ui,PlanningService service)throws IOException {
        this.service=service;this.ui=ui==null?null:ui.toAbsolutePath().normalize();
        server=HttpServer.create(new InetSocketAddress(host,port),32);
        server.setExecutor(executor);server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    private void handle(HttpExchange exchange)throws IOException {
        try {
            String origin=exchange.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&(origin.equals("http://127.0.0.1:5173")||origin.equals("http://localhost:5173"))) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin",origin);
                exchange.getResponseHeaders().set("Vary","Origin");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type");
            }
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            if("OPTIONS".equals(method)) { exchange.sendResponseHeaders(204,-1);return; }
            if("GET".equals(method)&&path.equals("/api/health")){send(exchange,200,Map.of("status","ok","service","shiftforge"));return;}
            if("GET".equals(method)&&path.equals("/api/examples")) {
                send(exchange,200,Examples.IDS.stream().map(id->Map.of("id",id,"scenario",Examples.load(id))).toList());return;
            }
            if("POST".equals(method)&&path.equals("/api/solve")) {
                byte[] bytes=exchange.getRequestBody().readNBytes(MAX_BODY+1);
                if(bytes.length>MAX_BODY){send(exchange,413,Map.of("detail","Request body exceeds 1 MiB"));return;}
                SolveRequest request=Inputs.request(Json.tree(new String(bytes,StandardCharsets.UTF_8)));
                if(!solveLock.tryLock()){send(exchange,409,Map.of("detail","A planning request is running in this API process; retry when it finishes."));return;}
                try {
                    Result result=service.solve(request);
                    int status=switch(result.status()){case "solver_unavailable"->503;case "solver_error","invalid_result"->500;default->200;};
                    send(exchange,status,result);
                } finally {solveLock.unlock();}
                return;
            }
            if(path.startsWith("/api/")){send(exchange,404,Map.of("detail","Unknown API route or method"));return;}
            if(ui!=null&&"GET".equals(method)) {
                Path file=ui.resolve(path.substring(1)).normalize();
                if(file.startsWith(ui)&&Files.isRegularFile(file)) {staticFile(exchange,file);return;}
                if(path.equals("/")&&Files.isRegularFile(ui.resolve("index.html"))){staticFile(exchange,ui.resolve("index.html"));return;}
            }
            send(exchange,404,Map.of("detail","Not found"));
        }catch(IllegalArgumentException e){sendIfOpen(exchange,422,Map.of("detail",e.getMessage()==null?"Invalid request":e.getMessage()));}
        catch(Exception e){sendIfOpen(exchange,500,Map.of("detail","Internal planning service error"));}
        finally { exchange.close(); }
    }
    private static void sendIfOpen(HttpExchange exchange,int code,Object value) {
        try {send(exchange,code,value);}catch(IOException ignored){}finally{exchange.close();}
    }
    private static void send(HttpExchange exchange,int code,Object value)throws IOException {
        byte[] bytes=Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
    }
    private static void staticFile(HttpExchange exchange,Path file)throws IOException {
        String name=file.getFileName().toString();
        String mime=name.endsWith(".html")?"text/html":name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":name.endsWith(".svg")?"image/svg+xml":"application/octet-stream";
        exchange.getResponseHeaders().set("Content-Type",mime);
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.sendResponseHeaders(200,Files.size(file));Files.copy(file,exchange.getResponseBody());
    }
    @Override public void close(){server.stop(0);executor.close();}
}
