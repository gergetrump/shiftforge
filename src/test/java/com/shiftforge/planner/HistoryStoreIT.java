package com.shiftforge.planner;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiftforge.planner.Domain.*;

/** Real PostgreSQL checks: this profile fails, rather than skips, if no test database is configured. */
class HistoryStoreIT {
    static DatabaseConfig config;
    HistoryStore store;
    @BeforeAll static void config() {
        String url=System.getenv("SHIFTFORGE_TEST_DATABASE_URL");
        assertNotNull(url,"SHIFTFORGE_TEST_DATABASE_URL is required for -Ppostgres");
        config=new DatabaseConfig(url,System.getenv().getOrDefault("SHIFTFORGE_TEST_DATABASE_USER","shiftforge"),
            System.getenv().getOrDefault("SHIFTFORGE_TEST_DATABASE_PASSWORD","local-demo-only"));
    }
    @BeforeEach void connect(){store=new HistoryStore(config);store.migrate();}
    @Test void storesExactSnapshotAndSurvivesNewStoreAndServer() throws Exception {
        var saved=store.createScenario(Examples.load("tiny"));
        var result=new PlanningService().solve(new SolveRequest(saved.scenario(),"greedy",10));
        var run=store.createRun(saved,result);
        assertEquals(saved,store.scenario(saved.id()));
        assertEquals(result,store.run(run.id()).result());
        HistoryStore restarted=new HistoryStore(config);restarted.migrate();
        assertEquals(saved,restarted.scenario(saved.id()));
        assertEquals(run,restarted.run(run.id()));
        assertTrue(restarted.scenarios(100,0).items().stream().anyMatch(x->x.id().equals(saved.id())));
        assertTrue(restarted.runs(100,0).items().stream().anyMatch(x->x.id().equals(run.id())));
        try(PlannerServer server=new PlannerServer("127.0.0.1",0,null,new PlanningService(),restarted)) {
            server.start();
            var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:"+server.port()+"/api/runs/"+run.id())).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals(result,Json.convert(Json.tree(response.body()).get("result"),Result.class));
        }
    }
    @Test void separatelySavedEditsCannotRewriteOldInputOrResult() {
        Scenario original=Examples.load("base");var first=store.createScenario(original);
        var run=store.createRun(first,new PlanningService().solve(new SolveRequest(original,"greedy",10)));
        var edited=store.createScenario(Examples.outage(original,"C3",5,6));
        assertNotEquals(first.scenario(),edited.scenario());
        assertEquals(original,store.scenario(first.id()).scenario());assertEquals(original,store.run(run.id()).result().input());
    }
    @Test void rejectedRunRollsBackAndLeavesNoPartialRow() {
        var saved=store.createScenario(Examples.load("tiny"));int before=store.runs(100,0).items().size();
        var wrong=new PlanningService().solve(new SolveRequest(Examples.load("lookahead"),"greedy",10));
        assertThrows(IllegalArgumentException.class,()->store.createRun(saved,wrong));
        assertEquals(before,store.runs(100,0).items().size());
        // Failure inside a real jOOQ transaction, after an insert, must roll it back.
        UUID id=UUID.randomUUID();
        assertThrows(RuntimeException.class,()->store.transaction(db->{
            db.insertInto(HistoryStore.SCENARIOS).set(HistoryStore.ID,id).set(HistoryStore.NAME,"rollback probe")
                .set(HistoryStore.SCENARIO_JSON,org.jooq.JSONB.valueOf(Json.write(saved.scenario()))).execute();
            throw new IllegalStateException("intentional transaction failure");
        }));
        assertThrows(HistoryStore.NotFound.class,()->store.scenario(id));
    }
    @Test void paginatesDeterministicallyAndRejectsMissingIds() {
        for(int i=0;i<3;i++)store.createScenario(Examples.load("tiny"));
        var first=store.scenarios(2,0);var next=store.scenarios(2,2);
        assertEquals(2,first.items().size());assertTrue(first.hasMore());
        assertFalse(first.items().getFirst().id().equals(next.items().getFirst().id()));
        assertThrows(HistoryStore.NotFound.class,()->store.scenario(UUID.randomUUID()));
        assertThrows(HistoryStore.NotFound.class,()->store.run(UUID.randomUUID()));
    }
}
