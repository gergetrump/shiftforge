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
}
