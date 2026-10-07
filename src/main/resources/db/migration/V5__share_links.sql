-- "anyone with the link": one link per document and permission; revoking deletes the row
CREATE TABLE share_links (
    token       VARCHAR(64) PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES documents (id),
    permission  VARCHAR(8)  NOT NULL CHECK (permission IN ('VIEW', 'EDIT')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, permission)
);
