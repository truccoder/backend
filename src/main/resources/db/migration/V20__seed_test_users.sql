-- Seed users for local/manual testing.
-- Password for all accounts below is: 12345678
-- BCrypt hash generated with the app's own BCryptPasswordEncoder (strength 10).
INSERT INTO socialapp.t_users (email, password, username, full_name, profile_picture_url) VALUES
    ('alice@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'alice', 'Alice Nguyen', NULL),
    ('bob@test.com',   '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'bob',   'Bob Tran',    NULL),
    ('carol@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'carol', 'Carol Le',    NULL),
    ('david@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'david', 'David Pham',  NULL),
    ('eve@test.com',   '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'eve',   'Eve Vo',      NULL)
ON CONFLICT (email) DO NOTHING;
