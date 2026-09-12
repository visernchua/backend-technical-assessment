-- REASON: Enforce balance and trade invariants even if a future code path bypasses validation.
ALTER TABLE user_wallets ALTER COLUMN balance SET NOT NULL;
ALTER TABLE user_wallets ADD CONSTRAINT wallet_nonnegative CHECK (balance >= 0);
ALTER TABLE crypto_pairs ADD CONSTRAINT different_symbols CHECK (base_symbol_id <> quote_symbol_id);
ALTER TABLE trades ADD CONSTRAINT positive_trade CHECK (quantity > 0 AND price > 0 AND total_amount > 0);
ALTER TABLE trades ALTER COLUMN trade_time SET NOT NULL;

-- REASON: Each best-price side may come from a different response. Preserve its actual receipt time.
-- Existing sample prices stay historical and cannot become executable just because the app starts.
ALTER TABLE crypto_prices ADD COLUMN bid_received_at TIMESTAMP(6);
ALTER TABLE crypto_prices ADD COLUMN ask_received_at TIMESTAMP(6);
ALTER TABLE crypto_prices ADD COLUMN bid_provider_at TIMESTAMP(6);
ALTER TABLE crypto_prices ADD COLUMN ask_provider_at TIMESTAMP(6);
ALTER TABLE crypto_prices ADD COLUMN eligible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crypto_prices ADD CONSTRAINT executable_price CHECK (NOT eligible OR
    (bid_received_at IS NOT NULL AND ask_received_at IS NOT NULL
     AND bid_price > 0 AND ask_price >= bid_price AND bid_source IN ('BINANCE','HUOBI') AND ask_source IN ('BINANCE','HUOBI')));
CREATE INDEX prices_latest ON crypto_prices(crypto_pair_id, created_at DESC, id DESC);

-- REASON: A completed request key prevents a retry after timeout from executing twice.
-- Existing sample trades have no request key or price provenance; do not invent historical values.
ALTER TABLE trades ADD COLUMN idempotency_key VARCHAR(128);
ALTER TABLE trades ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE trades ADD COLUMN price_snapshot_id BIGINT REFERENCES crypto_prices(id);
ALTER TABLE trades ADD COLUMN price_source VARCHAR(20);
ALTER TABLE trades ADD COLUMN price_observed_at TIMESTAMP(6);
ALTER TABLE trades ADD CONSTRAINT execution_metadata CHECK (idempotency_key IS NULL OR (
    request_fingerprint IS NOT NULL AND price_snapshot_id IS NOT NULL
    AND price_source IS NOT NULL AND price_source IN ('BINANCE','HUOBI') AND price_observed_at IS NOT NULL));
ALTER TABLE trades ADD CONSTRAINT unique_user_key UNIQUE(user_id,idempotency_key);
CREATE INDEX trades_user_time ON trades(user_id,trade_time DESC,id DESC);
