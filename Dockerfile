FROM node:24-bookworm-slim AS dashboard
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --ignore-scripts
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /build
COPY pom.xml ./
COPY src/ ./src/
RUN mvn -B verify

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system planner && useradd --system --gid planner --home-dir /app planner
COPY --from=backend /build/target/shiftforge.jar /app/shiftforge.jar
COPY --from=dashboard /build/frontend/dist /app/ui
USER planner
EXPOSE 8012
ENTRYPOINT ["java", "-jar", "/app/shiftforge.jar"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8012", "--ui", "/app/ui"]
