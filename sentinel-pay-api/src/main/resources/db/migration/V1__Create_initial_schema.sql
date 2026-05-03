-- V1__Create_initial_schema.sql
-- Initial schema for SentinelPay (Account + Transaction)

CREATE TABLE accounts (
                          id              UUID PRIMARY KEY,
                          account_number  VARCHAR(50) NOT NULL UNIQUE,
                          balance         NUMERIC(19,4) NOT NULL DEFAULT 0,
                          status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                          version         BIGINT NOT NULL DEFAULT 0,           -- For optimistic locking
                          created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                          updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE transactions (
                              id                UUID PRIMARY KEY,
                              account_id        UUID NOT NULL REFERENCES accounts(id),
                              amount            NUMERIC(19,4) NOT NULL,
                              currency          VARCHAR(3) NOT NULL DEFAULT 'LKR',
                              status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                              fraud_score       INTEGER,
                              failure_reason    TEXT,
                              idempotency_key   VARCHAR(100) UNIQUE,                 -- Important for idempotency
                              created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                              processed_at      TIMESTAMP WITH TIME ZONE
);

-- Indexes for performance (very important in fintech)
CREATE INDEX idx_transactions_account_id ON transactions(account_id);
CREATE INDEX idx_transactions_idempotency_key ON transactions(idempotency_key);
CREATE INDEX idx_accounts_account_number ON accounts(account_number);