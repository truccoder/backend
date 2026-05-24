CREATE SCHEMA IF NOT EXISTS socialapp;

CREATE SEQUENCE IF NOT EXISTS q_users_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE IF NOT EXISTS socialapp.t_users (
   id bigint DEFAULT nextval('q_users_id'),
    email VARCHAR(255) UNIQUE,
    password VARCHAR(255),
    username VARCHAR(255),
    full_name VARCHAR(255),
    profile_picture_url VARCHAR(1024),
    created_at TIMESTAMP with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP with time zone
);
