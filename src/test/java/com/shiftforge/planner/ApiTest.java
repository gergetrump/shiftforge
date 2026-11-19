package com.shiftforge.planner;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiftforge.planner.Domain.*;

class ApiTest {
    private final HttpClient client=HttpClient.newHttpClient();
    private HttpResponse<String> post(PlannerServer server,String json)throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/solve"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String body(String algorithm){return Json.write(new SolveRequest(Examples.load("tiny"),algorithm,10));}
    @Test void servesExamplesAndGreedyAndRejectsInvalidJson()throws Exception {
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,null,new PlanningService((s,t)->{throw new AssertionError("Unexpected optimizer");}))) {
            server.start();
            var examples=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/examples")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,examples.statusCode());assertEquals(3,Json.tree(examples.body()).size());
            var ok=post(server,body("greedy"));assertEquals(200,ok.statusCode());assertTrue(Json.tree(ok.body()).path("validation").path("valid").asBoolean());
            for(String bad:List.of("{", "{}", body("other"),body("greedy").replace("\"solver_time_limit_seconds\" : 10","\"solver_time_limit_seconds\" : true"))) {
                var response=post(server,bad);assertEquals(422,response.statusCode(),response.body());assertTrue(Json.tree(response.body()).has("detail"));
            }
            assertEquals(200,post(server,body("greedy")).statusCode());
        }
    }
    @Test void sends503AndReleasesLockAfterUnhandledException()throws Exception {
        AtomicBoolean first=new AtomicBoolean(true);
        PlanningService service=new PlanningService((s,t)->{if(first.getAndSet(false))throw new IllegalStateException("Injected bug");return PlanningService.result(s,"gurobi","solver_unavailable",Map.of(),null,null,null,"Test license missing");});
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,null,service)) {
            server.start();var error=post(server,body("gurobi"));assertEquals(500,error.statusCode());assertTrue(Json.tree(error.body()).has("detail"));
            var unavailable=post(server,body("gurobi"));assertEquals(503,unavailable.statusCode());
            assertEquals("solver_unavailable",Json.tree(unavailable.body()).path("status").asText());
            assertEquals(200,post(server,body("greedy")).statusCode());
        }
    }
    @Test void concurrentPlanningReturns409AndThenRecovers()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        PlanningService service=new PlanningService((s,t)->{
            entered.countDown();try{if(!finish.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
            return PlanningService.result(s,"gurobi","solver_unavailable",Map.of(),null,null,null,"Test unavailable");
        });
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,null,service);ExecutorService pool=Executors.newSingleThreadExecutor()) {
            server.start();Future<HttpResponse<String>> pending=pool.submit(()->post(server,body("gurobi")));
            try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(409,post(server,body("greedy")).statusCode());}finally{finish.countDown();}
            assertEquals(503,pending.get(5,TimeUnit.SECONDS).statusCode());assertEquals(200,post(server,body("greedy")).statusCode());
        }
    }
    @Test void staticFilesCannotEscapeThroughSymlinks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)throws Exception {
        var ui=java.nio.file.Files.createDirectory(temp.resolve("ui"));
        java.nio.file.Files.writeString(ui.resolve("index.html"),"<h1>Planner</h1>");
        var outside=java.nio.file.Files.writeString(temp.resolve("outside.txt"),"not a public asset");
        java.nio.file.Files.createSymbolicLink(ui.resolve("escape.txt"),outside);
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,ui,new PlanningService())) {
            server.start();
            var root=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,root.statusCode());assertTrue(root.body().contains("Planner"));
            var escape=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/escape.txt")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(404,escape.statusCode());assertFalse(escape.body().contains("not a public asset"));
        }
    }
    @Test void oversizedBodyReturnsJson413AndPlannerRemainsUsable()throws Exception {
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,null,new PlanningService())) {
            server.start();var tooLarge=post(server," ".repeat(1_048_577));
            assertEquals(413,tooLarge.statusCode());assertTrue(Json.tree(tooLarge.body()).has("detail"));
            assertEquals(200,post(server,body("greedy")).statusCode());
        }
    }

}
