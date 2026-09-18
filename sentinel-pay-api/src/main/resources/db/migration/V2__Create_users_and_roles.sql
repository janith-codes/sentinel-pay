-- V2__Create_users_and_roles.sql
-- Authentication identities and their role assignments.
-- account_id links an identity to the single payment account it may transact on.
-- Deliberately no seed data: credentials must never be shipped in a migration.

CREATE TABLE users (
                       id            UUID PRIMARY KEY,
                       username      VARCHAR(100) NOT NULL UNIQUE,
                       password_hash VARCHAR(255) NOT NULL,          -- BCrypt, never plain text
                       account_id    UUID REFERENCES accounts(id),
                       enabled       BOOLEAN NOT NULL DEFAULT TRUE,
                       created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                       updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE user_roles (
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            role    VARCHAR(20) NOT NULL,
                            PRIMARY KEY (user_id, role)
);

CREATE INDEX idx_users_account_id ON users(account_id);
