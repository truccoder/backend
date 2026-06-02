-- Books table
CREATE SEQUENCE IF NOT EXISTS q_books_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_books (
    id INT DEFAULT nextval('q_books_id') PRIMARY KEY,
    author_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    post_id INT REFERENCES socialapp.t_posts(id) ON DELETE SET NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    file_key VARCHAR(1024) NOT NULL,
    cover_image_url VARCHAR(2048),
    file_format VARCHAR(10) NOT NULL,
    file_size_bytes BIGINT,
    total_pages INT,
    preview_pages INT DEFAULT 0,
    price BIGINT DEFAULT 0,
    currency VARCHAR(3) DEFAULT 'VND',
    is_free BOOLEAN DEFAULT TRUE,
    download_count INT DEFAULT 0,
    avg_rating DECIMAL(2,1) DEFAULT 0.0,
    review_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_books_author_id ON socialapp.t_books(author_id);
CREATE INDEX idx_books_post_id ON socialapp.t_books(post_id);
CREATE INDEX idx_books_is_free ON socialapp.t_books(is_free);

-- Book reviews (ratings + feedback)
CREATE SEQUENCE IF NOT EXISTS q_book_reviews_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_book_reviews (
    id INT DEFAULT nextval('q_book_reviews_id') PRIMARY KEY,
    book_id INT NOT NULL REFERENCES socialapp.t_books(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    feedback TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(book_id, user_id)
);

CREATE INDEX idx_book_reviews_book_id ON socialapp.t_book_reviews(book_id);

-- Book purchases (payment records)
CREATE SEQUENCE IF NOT EXISTS q_book_purchases_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_book_purchases (
    id INT DEFAULT nextval('q_book_purchases_id') PRIMARY KEY,
    book_id INT NOT NULL REFERENCES socialapp.t_books(id) ON DELETE CASCADE,
    buyer_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) DEFAULT 'VND',
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    transaction_ref VARCHAR(100) UNIQUE,
    vnpay_transaction_no VARCHAR(100),
    payment_method VARCHAR(50),
    paid_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(book_id, buyer_id)
);

CREATE INDEX idx_book_purchases_buyer_id ON socialapp.t_book_purchases(buyer_id);
CREATE INDEX idx_book_purchases_transaction_ref ON socialapp.t_book_purchases(transaction_ref);
