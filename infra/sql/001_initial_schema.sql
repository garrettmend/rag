-- Abort the script immediately if any SQL statement fails to prevent partial execution
\set ON_ERROR_STOP on

-- RDS exposes pgvector as an approved extension; it still must be enabled in
-- each database. This script runs as the RDS master user in the migration
-- sidecar, not as either application role.
-- Enables the vector data type and similarity search functions.
CREATE EXTENSION IF NOT EXISTS vector;

-- The worker deliberately receives a permissive RLS policy below instead of
-- the PostgreSQL BYPASSRLS attribute. That keeps the role least-privileged and
-- works with the constrained superuser model used by Amazon RDS.
-- We use an anonymous PL/pgSQL block to conditionally create roles if they don't exist.
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
-- Sets passwords via variables and ensures roles cannot inherit permissions or create databases/roles.
ALTER ROLE rag_app WITH LOGIN NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'app_db_password';
ALTER ROLE rag_worker WITH LOGIN NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'worker_db_password';

-- Source-of-truth table for uploaded files and processing queue tracking
CREATE TABLE IF NOT EXISTS documents (
  id        uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  filename   text NOT NULL,
  file_hash  text NOT NULL,
  raw_text   text NOT NULL,
  -- State machine enforcement to track embedding progress
  status     text NOT NULL DEFAULT 'PENDING'
             CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  -- Prevents the same tenant from uploading the exact same file twice
  UNIQUE (tenant_id, file_hash)
);

-- Stores the segmented portions of documents, their embeddings, and text search vectors
CREATE TABLE IF NOT EXISTS chunks (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  -- ON DELETE CASCADE ensures chunks are wiped automatically if the parent document is deleted
  document_id uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  chunk_hash  text NOT NULL,
  content     text NOT NULL,
  -- 1024-dimensional vector column provided by pgvector
  embedding   vector(1024) NOT NULL,
  -- Automatically generates and stores a tsvector from English text for fast keyword search
  content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
  created_at  timestamptz NOT NULL DEFAULT now(),
  -- Prevents duplicate chunks for the same document
  UNIQUE (document_id, chunk_hash)
);

-- Hierarchical Navigable Small World (HNSW) index optimized for fast cosine distance similarity searches
CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw_idx
  ON chunks USING hnsw (embedding vector_cosine_ops);

-- Generalized Inverted Index (GIN) to accelerate full-text keyword searches on the auto-generated tsvector
CREATE INDEX IF NOT EXISTS chunks_content_tsv_idx
  ON chunks USING gin (content_tsv);

-- Standard B-tree index on tenant_id to prevent full-table scans during RLS filtering
CREATE INDEX IF NOT EXISTS chunks_tenant_id_idx
  ON chunks (tenant_id);

-- Lock down the public schema, removing default access for all users
REVOKE ALL ON SCHEMA public FROM PUBLIC;
-- Explicitly grant access only to our designated application roles
GRANT USAGE ON SCHEMA public TO rag_app, rag_worker;

-- Frontend App Permissions: Can upload files and read data, but cannot update statuses or create chunks
GRANT SELECT, INSERT ON documents TO rag_app;
GRANT SELECT ON chunks TO rag_app;

-- Background Worker Permissions: Can read files, update their statuses, and insert computed chunks
GRANT SELECT, UPDATE ON documents TO rag_worker;
-- SELECT is required because the worker's INSERT ... ON CONFLICT (document_id,
-- chunk_hash) reads the conflict-target columns.
GRANT SELECT, INSERT ON chunks TO rag_worker;

-- Enable and force Row Level Security (RLS) so it applies even to table owners
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
ALTER TABLE chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE chunks FORCE ROW LEVEL SECURITY;

-- App RLS Policy: Restricts the web app to only access rows matching the session's current_tenant_id
DROP POLICY IF EXISTS rag_app_documents_tenant_isolation ON documents;
CREATE POLICY rag_app_documents_tenant_isolation ON documents
  FOR ALL TO rag_app
  USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
  )
  WITH CHECK (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
  );

-- App RLS Policy: Same tenant isolation applied to the chunks table
DROP POLICY IF EXISTS rag_app_chunks_tenant_isolation ON chunks;
CREATE POLICY rag_app_chunks_tenant_isolation ON chunks
  FOR ALL TO rag_app
  USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
  )
  WITH CHECK (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
  );

-- Worker RLS Policy: Bypasses tenant isolation for the background processor so it can process all files globally
DROP POLICY IF EXISTS rag_worker_documents ON documents;
CREATE POLICY rag_worker_documents ON documents
  FOR ALL TO rag_worker
  USING (true)
  WITH CHECK (true);

-- Worker RLS Policy: Global access for chunks table
DROP POLICY IF EXISTS rag_worker_chunks ON chunks;
CREATE POLICY rag_worker_chunks ON chunks
  FOR ALL TO rag_worker
  USING (true)
  WITH CHECK (true);