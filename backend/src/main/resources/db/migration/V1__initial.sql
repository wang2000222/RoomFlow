CREATE TABLE IF NOT EXISTS store (id BIGINT PRIMARY KEY, name VARCHAR(100), address VARCHAR(255));
CREATE TABLE IF NOT EXISTS room (id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL REFERENCES store(id), name VARCHAR(80), capacity INT, price_cents INT NOT NULL, state VARCHAR(30) NOT NULL);
CREATE TABLE IF NOT EXISTS booking (id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(80) NOT NULL, room_id BIGINT NOT NULL REFERENCES room(id), starts_at BIGINT NOT NULL, ends_at BIGINT NOT NULL, amount_cents BIGINT NOT NULL, state VARCHAR(30) NOT NULL, request_key VARCHAR(80) NOT NULL, created_at BIGINT NOT NULL, UNIQUE(user_id, request_key));
CREATE INDEX ix_booking_room ON booking(room_id, starts_at, ends_at);
CREATE TABLE IF NOT EXISTS audit (id VARCHAR(36) PRIMARY KEY, actor VARCHAR(80), action VARCHAR(80), target VARCHAR(80), created_at BIGINT NOT NULL);
