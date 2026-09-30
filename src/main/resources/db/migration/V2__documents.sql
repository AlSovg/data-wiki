CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL REFERENCES users (id),
    title           VARCHAR(255) NOT NULL,
    category        VARCHAR(255),
    author          VARCHAR(255) NOT NULL,
    current_version INT          NOT NULL,
    content_hash    CHAR(64)     NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    word_count      INT          NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

-- duplicates are detected per owner among live documents
CREATE UNIQUE INDEX documents_owner_hash_live ON documents (owner_id, content_hash) WHERE deleted_at IS NULL;
CREATE INDEX documents_owner_updated ON documents (owner_id, updated_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE document_versions (
    document_id UUID        NOT NULL REFERENCES documents (id),
    version     INT         NOT NULL,
    content     TEXT        NOT NULL,
    extra       JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID        NOT NULL REFERENCES users (id),
    PRIMARY KEY (document_id, version)
);

-- tags are per owner, otherwise tag names leak between users
CREATE TABLE tags (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id UUID         NOT NULL REFERENCES users (id),
    name     VARCHAR(255) NOT NULL,
    UNIQUE (owner_id, name)
);

CREATE TABLE document_tags (
    document_id UUID   NOT NULL REFERENCES documents (id),
    tag_id      BIGINT NOT NULL REFERENCES tags (id),
    PRIMARY KEY (document_id, tag_id)
);
CREATE INDEX document_tags_tag ON document_tags (tag_id);
