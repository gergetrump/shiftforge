package com.shiftforge.planner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.shiftforge.planner.Domain.*;

public final class Examples {
    public static final List<String> IDS=List.of("base","tiny","lookahead");
    private Examples() {}
    public static Scenario load(String id) {
        Inputs.require(IDS.contains(id),"Unknown example: "+id);
        try(InputStream in=Examples.class.getResourceAsStream("/examples/"+id+".json")) {
            if(in==null)throw new IllegalStateException("Missing packaged example");
            return Inputs.scenario(Json.tree(new String(in.readAllBytes(),StandardCharsets.UTF_8)));
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    public static Scenario outage(Scenario s,String cell,int start,int end) {
        Inputs.require(s.cells().stream().anyMatch(c->c.id().equals(cell))&&start>=1&&start<=end&&end<=s.shifts().size(),"Outage needs a known cell and an ordered shift range");
        Set<String> affected=new HashSet<>();s.shifts().stream().filter(t->t.index()>=start&&t.index()<=end).forEach(t->affected.add(t.id()));
        List<Capacity> changed=s.capacity().stream().map(c->new Capacity(c.cellId(),c.shiftId(),c.cellId().equals(cell)&&affected.contains(c.shiftId())?0:c.availableMachineMinutes())).toList();
        String suffix=" · "+cell+" outage, shifts "+start+"–"+end;
        int retain=Math.min(s.name().codePointCount(0,s.name().length()),160-suffix.codePointCount(0,suffix.length()));
        String name=s.name().substring(0,s.name().offsetByCodePoints(0,retain))+suffix;
        return new Scenario(s.schemaVersion(),name,s.dataOrigin(),s.products(),s.cells(),s.processingTimes(),s.shifts(),changed,s.demand());
    }
}
