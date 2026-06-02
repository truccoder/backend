ALTER TABLE socialapp.t_posts ADD COLUMN post_type VARCHAR(20) DEFAULT 'REGULAR';
ALTER TABLE socialapp.t_posts ADD COLUMN event_details JSONB;

CREATE INDEX idx_posts_post_type ON socialapp.t_posts(post_type);

-- RSVP table for event attendance
CREATE SEQUENCE IF NOT EXISTS q_event_rsvps_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_event_rsvps (
    id INT DEFAULT nextval('q_event_rsvps_id') PRIMARY KEY,
    post_id INT NOT NULL REFERENCES socialapp.t_posts(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'GOING',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(post_id, user_id)
);

CREATE INDEX idx_event_rsvps_post_id ON socialapp.t_event_rsvps(post_id);

-- Google Calendar OAuth tokens
CREATE TABLE socialapp.t_google_calendar_tokens (
    user_id INT PRIMARY KEY REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    access_token TEXT NOT NULL,
    refresh_token TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
