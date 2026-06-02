CREATE SEQUENCE IF NOT EXISTS q_trending_items_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_trending_items (
    id INT DEFAULT nextval('q_trending_items_id') PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    summary TEXT,
    url VARCHAR(2048) NOT NULL,
    image_url VARCHAR(2048),
    source VARCHAR(50) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    category VARCHAR(50) NOT NULL,
    tags JSONB DEFAULT '[]'::jsonb,
    score INT DEFAULT 0,
    author VARCHAR(255),
    published_at TIMESTAMP WITH TIME ZONE,
    crawled_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source, source_id)
);

CREATE INDEX idx_trending_items_category ON socialapp.t_trending_items(category);
CREATE INDEX idx_trending_items_published_at ON socialapp.t_trending_items(published_at DESC);
CREATE INDEX idx_trending_items_score ON socialapp.t_trending_items(score DESC);
CREATE INDEX idx_trending_items_source ON socialapp.t_trending_items(source);
