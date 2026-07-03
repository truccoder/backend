ALTER TABLE socialapp.t_book_purchases RENAME COLUMN vnpay_transaction_no TO gateway_transaction_no;
ALTER TABLE socialapp.t_book_purchases ADD COLUMN payment_link_id VARCHAR(100);
