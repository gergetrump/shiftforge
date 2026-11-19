package com.shiftforge.planner;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiftforge.planner.Domain.*;

class InputsTest {
    @ParameterizedTest @ValueSource(strings={"-1","0.5","true","\"1\"","null","10001"})
    void rejectsNonIntegerAndOutOfRangeDemand(String bad) {
        ObjectNode tree=Json.MAPPER.valueToTree(Examples.load("tiny"));
        ((ObjectNode)tree.withArray("demand").get(0)).set("due_units",Json.tree(bad));
        assertThrows(IllegalArgumentException.class,()->Inputs.scenario(tree));
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","missing","reference","duration","indices","unknown","nullRow","missingQuantity","emptyProducts","wrongId"})
    void rejectsInvalidStructures(String change) {
        ObjectNode tree=Json.MAPPER.valueToTree(Examples.load("tiny"));
        switch(change) {
            case "duplicate"->tree.withArray("products").add(tree.withArray("products").get(0).deepCopy());
            case "missing"->tree.withArray("capacity").remove(0);
            case "reference"->((ObjectNode)tree.withArray("processing_times").get(0)).put("cell_id","unknown");
            case "duration"->((ObjectNode)tree.withArray("capacity").get(0)).put("available_machine_minutes",3);
            case "indices"->((ObjectNode)tree.withArray("shifts").get(0)).put("index",2);
            case "unknown"->((ObjectNode)tree.withArray("shifts").get(0)).put("extra",1);
            case "nullRow"->tree.withArray("products").set(0,NullNode.instance);
            case "missingQuantity"->((ObjectNode)tree.withArray("products").get(0)).remove("initial_inventory_units");
            case "emptyProducts"->tree.withArray("products").removeAll();
            case "wrongId"->((ObjectNode)tree.withArray("cells").get(0)).put("id","bad id");
        }
        assertThrows(IllegalArgumentException.class,()->Inputs.scenario(tree));
    }
    @Test void requestDefaultsAndImmutableInputs() {
        Scenario s=Examples.load("base");ObjectNode request=Json.MAPPER.createObjectNode().set("scenario",Json.MAPPER.valueToTree(s));
        SolveRequest parsed=Inputs.request(request);assertEquals("gurobi",parsed.algorithm());assertEquals(10,parsed.solverTimeLimitSeconds());
        assertThrows(UnsupportedOperationException.class,()->s.capacity().clear());
        Scenario outage=Examples.outage(s,"C3",5,6);
        assertEquals(2,outage.capacity().stream().filter(c->c.availableMachineMinutes()==0).count());
        assertEquals(0,s.capacity().stream().filter(c->c.availableMachineMinutes()==0).count());
    }
    @Test void rejectsDuplicateKeysAndTrailingJson() {
        assertThrows(IllegalArgumentException.class,()->Json.tree("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class,()->Json.tree("{} {}"));
    }
}
