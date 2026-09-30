-- "This document changed, bring the Lucene index in line with PostgreSQL". The worker decides between
-- upsert and delete from the document's current state, so a task carries no action. Done tasks are deleted;
-- tasks that reached the attempt limit stay for inspection.
CREATE TABLE index_tasks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES documents (id),
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX index_tasks_document ON index_tasks (document_id);
