package io.github.caiocv18.turnward.adapter.persistence;

import org.testcontainers.utility.DockerImageName;

/** The one Postgres image the tests use: pgvector pinned to the version RDS ships, never the upstream latest. */
public final class PostgresImage {

    public static final DockerImageName NAME =
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg18").asCompatibleSubstituteFor("postgres");

    private PostgresImage() {}
}
