CREATE SEQUENCE IF NOT EXISTS q_friend_requests_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_friend_requests (
     id int DEFAULT nextval('q_friend_requests_id') PRIMARY KEY,
     requester_id INT,
     addressee_id INT,
     status VARCHAR,
     created_at TIMESTAMP with time zone DEFAULT CURRENT_TIMESTAMP,
     updated_at TIMESTAMP with time zone,

     CONSTRAINT fk_friend_requests_requester
         FOREIGN KEY (requester_id)
             REFERENCES socialapp.t_users(id)
             ON DELETE CASCADE,

     CONSTRAINT fk_friend_requests_addressee
         FOREIGN KEY (addressee_id)
             REFERENCES socialapp.t_users(id)
             ON DELETE CASCADE
);