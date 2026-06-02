CREATE SEQUENCE IF NOT EXISTS q_notifications_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_notifications (
    id INT DEFAULT nextval('q_notifications_id') PRIMARY KEY,
    recipient_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    actor_id INT REFERENCES socialapp.t_users(id) ON DELETE SET NULL,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT,
    reference_id INT,
    reference_type VARCHAR(50),
    channel VARCHAR(20) NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_recipient ON socialapp.t_notifications(recipient_id, is_read);
CREATE INDEX idx_notifications_created_at ON socialapp.t_notifications(recipient_id, created_at DESC);

-- User notification preferences
CREATE TABLE socialapp.t_notification_preferences (
    user_id INT PRIMARY KEY REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    push_enabled BOOLEAN DEFAULT TRUE,
    email_enabled BOOLEAN DEFAULT TRUE,
    onesignal_player_id VARCHAR(255),
    email_frequency VARCHAR(20) DEFAULT 'INSTANT',
    muted_types JSONB DEFAULT '[]'::jsonb,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
