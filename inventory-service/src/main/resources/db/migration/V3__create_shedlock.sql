-- Lock table for ShedLock (JdbcTemplateLockProvider). The name must be the primary key.
-- Same SQL runs on PostgreSQL and H2.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
