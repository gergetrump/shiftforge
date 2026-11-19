package com.shiftforge.planner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import static com.shiftforge.planner.Domain.*;

/** Append-only history. All application reads/writes use typed jOOQ DSL and bound values. */
public final class HistoryStore {
    static final Table<org.jooq.Record> SCENARIOS = DSL.table(DSL.name("saved_scenarios"));
    static final Table<org.jooq.Record> RUNS = DSL.table(DSL.name("planning_runs"));
    static final Field<UUID> ID = DSL.field(DSL.name("id"), UUID.class);
    static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
    static final Field<JSONB> SCENARIO_JSON = DSL.field(DSL.name("scenario_json"), JSONB.class);
    static final Field<UUID> SCENARIO_ID = DSL.field(DSL.name("scenario_id"), UUID.class);
    static final Field<JSONB> INPUT = DSL.field(DSL.name("input_snapshot"), JSONB.class);
    static final Field<JSONB> RESULT = DSL.field(DSL.name("result_json"), JSONB.class);
    static final Field<String> ALGORITHM = DSL.field(DSL.name("algorithm"), String.class);
    static final Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
    static final Field<OffsetDateTime> CREATED = DSL.field(DSL.name("created_at"), OffsetDateTime.class);
    private final DatabaseConfig config;
    public HistoryStore(DatabaseConfig config) { this.config = Objects.requireNonNull(config); }
    public record SavedScenario(UUID id, String name, Scenario scenario, String createdAt) {}
    public record ScenarioSummary(UUID id, String name, String createdAt) {}
    public record SavedRun(UUID id, UUID scenarioId, String scenarioName, Result result, String createdAt) {}
    public record RunSummary(UUID id, UUID scenarioId, String scenarioName, String algorithm, String status, String createdAt) {}
    public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) { public Page { items = List.copyOf(items); } }
    public static final class NotFound extends RuntimeException { public NotFound() { super("Saved item not found"); } }
    public static final class Unavailable extends RuntimeException { public Unavailable(Throwable cause) { super("History storage is unavailable; nothing was reported as saved", cause); } }

    private <T> T read(Function<DSLContext,T> operation) {
        try (Connection connection = config.connect()) {
            return operation.apply(DSL.using(connection, SQLDialect.POSTGRES, new Settings().withQueryTimeout(5).withExecuteLogging(false)));
        } catch (SQLException | org.jooq.exception.DataAccessException e) { throw new Unavailable(e); }
    }
    <T> T transactionResult(Function<DSLContext,T> operation) {
        return read(db -> db.transactionResult(configuration -> operation.apply(DSL.using(configuration))));
    }
    void transaction(Consumer<DSLContext> operation) { transactionResult(db -> { operation.accept(db); return null; }); }
    public void migrate() {
        String sql;
        try (InputStream in = HistoryStore.class.getResourceAsStream("/db/V001__history.sql")) {
            sql = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Missing migration", e); }
        String checksum;
        try { checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        transaction(db -> {
            db.execute("select pg_advisory_xact_lock(729340012)");
            db.execute("create table if not exists shiftforge_migrations (version integer primary key, checksum text not null)");
            String existing = db.select(DSL.field(DSL.name("checksum"), String.class)).from(DSL.table(DSL.name("shiftforge_migrations")))
                .where(DSL.field(DSL.name("version"), Integer.class).eq(1)).fetchOne(0, String.class);
            if (existing != null) { if (!checksum.equals(existing)) throw new IllegalStateException("Migration checksum mismatch"); return; }
            for (String statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
            db.insertInto(DSL.table(DSL.name("shiftforge_migrations"))).columns(DSL.field(DSL.name("version"), Integer.class), DSL.field(DSL.name("checksum"), String.class)).values(1, checksum).execute();
        });
    }
    public SavedScenario createScenario(Scenario scenario) {
        // Validate even callers outside HTTP. JSONB retains array ordering and integer values.
        Scenario checked = Inputs.scenario(Json.MAPPER.valueToTree(scenario)); UUID id = UUID.randomUUID();
        return transactionResult(db -> scenario(db.insertInto(SCENARIOS).set(ID,id).set(NAME,checked.name())
            .set(SCENARIO_JSON,JSONB.valueOf(Json.write(checked))).returning(ID,NAME,SCENARIO_JSON,CREATED).fetchOne()));
    }
    public SavedScenario scenario(UUID id) { return read(db -> scenario(db.select(ID,NAME,SCENARIO_JSON,CREATED).from(SCENARIOS).where(ID.eq(id)).fetchOne())); }
    private static SavedScenario scenario(org.jooq.Record row) {
        if (row == null) throw new NotFound();
        return new SavedScenario(row.get(ID), row.get(NAME), Inputs.scenario(Json.tree(row.get(SCENARIO_JSON).data())), row.get(CREATED).toInstant().toString());
    }
    public SavedRun createRun(SavedScenario saved, Result result) {
        Inputs.require(saved.scenario().equals(result.input()), "Run input must match its immutable saved scenario");
        if (Set.of("optimal","feasible","heuristic_feasible").contains(result.status()))
            Inputs.require(Plans.check(saved.scenario(), result.solution()).valid(), "Cannot save an invalid accepted solution");
        UUID id = UUID.randomUUID();
        return transactionResult(db -> {
            // Re-read within the transaction; stale/invented scenario objects cannot substitute another input.
            SavedScenario current = scenario(db.select(ID,NAME,SCENARIO_JSON,CREATED).from(SCENARIOS).where(ID.eq(saved.id())).fetchOne());
            Inputs.require(current.scenario().equals(result.input()), "Run snapshot differs from saved scenario");
            var row = db.insertInto(RUNS).set(ID,id).set(SCENARIO_ID,saved.id()).set(INPUT,JSONB.valueOf(Json.write(result.input())))
                .set(RESULT,JSONB.valueOf(Json.write(result))).set(ALGORITHM,result.algorithm()).set(STATUS,result.status())
                .returning(ID,SCENARIO_ID,RESULT,CREATED).fetchOne();
            return run(row,current.name());
        });
    }
    public SavedRun run(UUID id) {
        return read(db -> {
            var row = db.select(ID,SCENARIO_ID,RESULT,CREATED).from(RUNS).where(ID.eq(id)).fetchOne();
            if (row == null) throw new NotFound();
            String name = db.select(NAME).from(SCENARIOS).where(ID.eq(row.get(SCENARIO_ID))).fetchOne(NAME);
            return run(row,name);
        });
    }
    private static SavedRun run(org.jooq.Record row, String name) { return new SavedRun(row.get(ID),row.get(SCENARIO_ID),name,Json.convert(Json.tree(row.get(RESULT).data()),Result.class),row.get(CREATED).toInstant().toString()); }
    static void pageBounds(int limit,int offset) { Inputs.require(limit>=1&&limit<=100&&offset>=0&&offset<=1_000_000,"Pagination requires limit 1..100 and offset 0..1000000"); }
    public Page<ScenarioSummary> scenarios(int limit,int offset) {
        pageBounds(limit,offset);
        return read(db -> {
            var rows = db.select(ID,NAME,CREATED).from(SCENARIOS).orderBy(CREATED.desc(),ID.desc()).limit(limit+1).offset(offset).fetch();
            return new Page<>(rows.stream().limit(limit).map(r->new ScenarioSummary(r.get(ID),r.get(NAME),r.get(CREATED).toInstant().toString())).toList(),limit,offset,rows.size()>limit);
        });
    }
    public Page<RunSummary> runs(int limit,int offset) {
        pageBounds(limit,offset);
        return read(db -> {
            Table<?> runs = RUNS.as("r"), scenarios = SCENARIOS.as("s");
            Field<UUID> rid=DSL.field(DSL.name("r","id"),UUID.class), sid=DSL.field(DSL.name("r","scenario_id"),UUID.class);
            Field<String> name=DSL.field(DSL.name("s","name"),String.class), algorithm=DSL.field(DSL.name("r","algorithm"),String.class), status=DSL.field(DSL.name("r","status"),String.class);
            Field<OffsetDateTime> created=DSL.field(DSL.name("r","created_at"),OffsetDateTime.class);
            var rows=db.select(rid,sid,name,algorithm,status,created).from(runs).join(scenarios).on(sid.eq(DSL.field(DSL.name("s","id"),UUID.class)))
                .orderBy(created.desc(),rid.desc()).limit(limit+1).offset(offset).fetch();
            return new Page<>(rows.stream().limit(limit).map(r->new RunSummary(r.get(rid),r.get(sid),r.get(name),r.get(algorithm),r.get(status),r.get(created).toInstant().toString())).toList(),limit,offset,rows.size()>limit);
        });
    }
}
