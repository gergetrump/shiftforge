package com.shiftforge.planner;

import java.sql.*;
import java.util.*;

/** Credentials stay in environment variables and are never returned through the API. */
public record DatabaseConfig(String url, String user, String password) {
    public DatabaseConfig {
        Inputs.require(url != null && url.startsWith("jdbc:postgresql://"), "Database URL must be jdbc:postgresql://...");
        Inputs.require(user != null && !user.isBlank(), "Database user is required");
        Inputs.require(password != null, "Database password is required");
    }
    public static Optional<DatabaseConfig> fromEnvironment() {
        Map<String,String> env = System.getenv();
        String url = env.get("SHIFTFORGE_DATABASE_URL");
        if (url == null || url.isBlank()) return Optional.empty();
        return Optional.of(new DatabaseConfig(url, env.get("SHIFTFORGE_DATABASE_USER"), env.get("SHIFTFORGE_DATABASE_PASSWORD")));
    }
    Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user); properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "3"); properties.setProperty("socketTimeout", "5");
        properties.setProperty("ApplicationName", "shiftforge");
        return DriverManager.getConnection(url, properties);
    }
    @Override public String toString() { return "DatabaseConfig[configured PostgreSQL; credentials redacted]"; }
}
