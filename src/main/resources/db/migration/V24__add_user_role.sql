ALTER TABLE socialapp.t_users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

ALTER TABLE socialapp.t_users
    ADD CONSTRAINT chk_t_users_role CHECK (role IN ('USER', 'ADMIN'));

CREATE INDEX idx_users_role ON socialapp.t_users (role);
