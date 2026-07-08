-- sendFriendRequest()'s duplicate-pending check was a plain read-then-insert with no locking,
-- so two near-simultaneous mutual requests between the same pair of users could both pass the
-- check and insert two PENDING rows. This partial unique index (direction-independent, via
-- LEAST/GREATEST) is the DB-level safety net; FriendshipService now translates the resulting
-- constraint violation into the same friendly 400 the pre-check already returns.
CREATE UNIQUE INDEX uq_friend_requests_pending_pair ON socialapp.t_friend_requests (LEAST(requester_id, addressee_id),
                                                                                    GREATEST(requester_id, addressee_id)) WHERE status = 'PENDING';
