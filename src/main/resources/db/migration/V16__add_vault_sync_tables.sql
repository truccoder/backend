-- Vault notes synced from user's Obsidian
CREATE SEQUENCE IF NOT EXISTS q_vault_notes_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_vault_notes (
    id INT DEFAULT nextval('q_vault_notes_id') PRIMARY KEY,
    user_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    filename VARCHAR(500) NOT NULL,
    content TEXT,
    tags JSONB DEFAULT '[]'::jsonb,
    links JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, filename)
);

CREATE INDEX idx_vault_notes_user_id ON socialapp.t_vault_notes(user_id);

-- Add vault permission to access token table
ALTER TABLE socialapp.t_personal_access_tokens ADD COLUMN vault_permission VARCHAR(20) DEFAULT 'WRITE_ONLY';
