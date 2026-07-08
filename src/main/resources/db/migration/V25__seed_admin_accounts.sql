-- Seed admin accounts for moderation review/verification access.
-- Password for both accounts below is: 12345678a
-- BCrypt hash generated with the app's own BCryptPasswordEncoder (strength 10).
INSERT INTO socialapp.t_users (email, password, username, full_name, role, email_verified)
VALUES ('admin1@socialapp.com', '$2a$10$I2vbfmkeuMkKC8FpgB19lezCGvlAqtPDezRu49acMDaqBLmfPzPdS', 'admin1', 'Admin One',
        'ADMIN', TRUE),
       ('admin2@socialapp.com', '$2a$10$aLq4ia8aPYg8h1d.Yp.Ga.Nf0UXXuwst5.VorR4PAEg2ARuhJRoGK', 'admin2', 'Admin Two',
        'ADMIN', TRUE)
ON CONFLICT (email) DO NOTHING;
