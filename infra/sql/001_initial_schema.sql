\set ON_ERROR_STOP on

-- RDS exposes pgvector as an approved extension; it still must be enabled in
-- each database. This script runs as the RDS master user in the migration
-- sidecar, not as either application role.
CREATE EXTENSION IF NOT EXISTS vector;

-- The worker deliberately receives a permissive RLS policy below instead of
-- the PostgreSQL BYPASSRLS attribute. That keeps the role least-privileged and
-- works with the constrained superuser model used by Amazon RDS.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rag_app') THEN
    CREATE ROLE rag_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rag_worker') THEN
    CREATE ROLE rag_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END
$$;

-- SUPERUSER is omitted here: RDS's master user is not a true superuser, and
-- PostgreSQL rejects any ALTER ROLE that mentions that attribute, even NOSUPERUSER.
ALTER ROLE rag_app WITH LOGIN NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'app_db_password';
ALTER ROLE rag_worker WITH LOGIN NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'worker_db_password';

CREATE TABLE IF NOT EXISTS documents (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  filename   text NOT NULL,
  file_hash  text NOT NULL,
  raw_text   text NOT NULL,
  status     text NOT NULL DEFAULT 'PENDING'
             CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, file_hash)
);

CREATE TABLE IF NOT EXISTS chunks (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  document_id uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  chunk_hash  text NOT NULL,
  content     text NOT NULL,
  embedding   vector(1024) NOT NULL,
  content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (document_id, chunk_hash)
);

CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw_idx
  ON chunks USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS chunks_content_tsv_idx
  ON chunks USING gin (content_tsv);

CREATE INDEX IF NOT EXISTS chunks_tenant_id_idx
  ON chunks (tenant_id);

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO rag_app, rag_worker;

GRANT SELECT, INSERT ON documents TO rag_app;
GRANT SELECT ON chunks TO rag_app;

GRANT SELECT, UPDATE ON documents TO rag_worker;
-- SELECT is required because the worker's INSERT ... ON CONFLICT (document_id,
-- chunk_hash) reads the conflict-target columns.
GRANT SELECT, INSERT ON chunks TO rag_worker;

ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
ALTER TABLE chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE chunks FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rag_app_documents_tenant_isolation ON documents;
CREATE POLICY rag_app_documents_tenant_isolation ON documents
  FOR ALL TO rag_app
  USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
  )
  WITH CHECK (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
  );

DROP POLICY IF EXISTS rag_app_chunks_tenant_isolation ON chunks;
CREATE POLICY rag_app_chunks_tenant_isolation ON chunks
  FOR ALL TO rag_app
  USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
  )
  WITH CHECK (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
  );

DROP POLICY IF EXISTS rag_worker_documents ON documents;
CREATE POLICY rag_worker_documents ON documents
  FOR ALL TO rag_worker
  USING (true)
  WITH CHECK (true);

DROP POLICY IF EXISTS rag_worker_chunks ON chunks;
CREATE POLICY rag_worker_chunks ON chunks
  FOR ALL TO rag_worker
  USING (true)
  WITH CHECK (true);
