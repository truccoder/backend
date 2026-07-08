-- Needed to tell a stale/abandoned pending MoMo payment attempt apart from one that might
-- still be actively in progress, so createPayment() can decide whether it's safe to overwrite
-- transaction_ref (see MomoService.createPayment()).
ALTER TABLE socialapp.t_book_purchases
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

UPDATE socialapp.t_book_purchases
SET updated_at = created_at
WHERE updated_at IS NULL;
