DROP TABLE socialapp.t_users;

CREATE TABLE IF NOT EXISTS socialapp.t_users (
    id int DEFAULT nextval('q_users_id') PRIMARY KEY,
    email VARCHAR UNIQUE,
    password VARCHAR,
    username VARCHAR,
    full_name VARCHAR,
    profile_picture_url VARCHAR,
    created_at TIMESTAMP with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP with time zone
);