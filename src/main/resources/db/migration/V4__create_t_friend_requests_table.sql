CREATE TABLE users.t_friend_requests (
     id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
         requester_id INT,
     addressee_id INT,
     status VARCHAR(50),
     created_at TIMESTAMP with time zone DEFAULT CURRENT_TIMESTAMP,
     updated_at TIMESTAMP with time zone
);