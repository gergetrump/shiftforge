package com.shiftforge.planner;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryApiIT {
    private final HttpClient client=HttpClient.newHttpClient();
    private HttpResponse<String> request(PlannerServer server,String path,String body)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+path)).timeout(java.time.Duration.ofSeconds(15));
        if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void createsRetrievesListsAndRejectsMalformedHistoryRequests()throws Exception {
        HistoryStoreIT.config();HistoryStore store=new HistoryStore(HistoryStoreIT.config);store.migrate();
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,null,new PlanningService(),store)) {
            server.start();
            var saved=request(server,"/api/scenarios",Json.write(Examples.load("lookahead")));
            assertEquals(201,saved.statusCode());String id=Json.tree(saved.body()).path("id").asText();
            assertEquals(Examples.load("lookahead"),Json.convert(Json.tree(saved.body()).get("scenario"),Domain.Scenario.class));
            assertEquals(200,request(server,"/api/scenarios/"+id,null).statusCode());
            var run=request(server,"/api/runs","{\"scenario_id\":\""+id+"\",\"algorithm\":\"greedy\"}");
            assertEquals(201,run.statusCode());var tree=Json.tree(run.body());
            assertEquals("heuristic_feasible",tree.path("result").path("status").asText());
            assertEquals(200,request(server,"/api/runs/"+tree.path("id").asText(),null).statusCode());
            assertTrue(Json.tree(request(server,"/api/runs?limit=1&offset=0",null).body()).path("items").isArray());
            assertEquals(404,request(server,"/api/runs/"+UUID.randomUUID(),null).statusCode());
            assertEquals(404,request(server,"/api/scenarios/"+UUID.randomUUID(),null).statusCode());
            for(String path:List.of("/api/runs?limit=101","/api/scenarios?offset=-1","/api/scenarios?limit=true","/api/runs?limit=1&limit=2","/api/scenarios/not-a-uuid"))
                assertEquals(422,request(server,path,null).statusCode(),path);
            assertEquals(422,request(server,"/api/scenarios","{}").statusCode());
            assertEquals(422,request(server,"/api/runs","{\"scenario_id\":\""+id+"\",\"result\":{}}").statusCode());
            assertEquals(422,request(server,"/api/runs","{\"scenario_id\":\""+id+"\",\"solver_time_limit_seconds\":true}").statusCode());
        }
    }
    @Test void unavailableDatabaseNeverClaimsSavedAndEphemeralSolveStillWorks()throws Exception {
        HistoryStore broken=new HistoryStore(new DatabaseConfig("jdbc:postgresql://127.0.0.1:1/absent","demo","not-a-real-password"));
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,null,new PlanningService(),broken)) {
            server.start();
            for(String path:List.of("/api/scenarios","/api/runs")) {
                var response=request(server,path,null);assertEquals(503,response.statusCode());
                assertTrue(Json.tree(response.body()).has("detail"));assertFalse(Json.tree(response.body()).has("id"));
            }
            assertEquals(503,request(server,"/api/scenarios",Json.write(Examples.load("tiny"))).statusCode());
            assertEquals(503,request(server,"/api/runs","{\"scenario_id\":\""+UUID.randomUUID()+"\",\"algorithm\":\"greedy\"}").statusCode());
            var solve=request(server,"/api/solve",Json.write(new Domain.SolveRequest(Examples.load("tiny"),"greedy",10)));
            assertEquals(200,solve.statusCode());assertTrue(Json.tree(solve.body()).path("validation").path("valid").asBoolean());
        }
    }
    @Test void noDatabaseModeIsExplicit()throws Exception {
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,null,new PlanningService())) {
            server.start();assertFalse(Json.tree(request(server,"/api/health",null).body()).path("history_enabled").asBoolean());
            assertEquals(503,request(server,"/api/scenarios",null).statusCode());
        }
    }
}
