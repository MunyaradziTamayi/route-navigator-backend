CREATE TABLE IF NOT EXISTS routes (
    id               BIGSERIAL PRIMARY KEY,
    origin_name      VARCHAR(255),
    destination_name VARCHAR(255),
    origin_lat       DOUBLE PRECISION,
    origin_lon       DOUBLE PRECISION,
    destination_lat  DOUBLE PRECISION,
    destination_lon  DOUBLE PRECISION,
    distance_km      DOUBLE PRECISION,
    duration_seconds INTEGER,
    geometry         TEXT,
    created_at       TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS visits (
    id                BIGSERIAL PRIMARY KEY,
    origin_label      VARCHAR(500),
    destination_label VARCHAR(500),
    origin_lat        DOUBLE PRECISION,
    origin_lon        DOUBLE PRECISION,
    destination_lat   DOUBLE PRECISION,
    destination_lon   DOUBLE PRECISION,
    client_label      VARCHAR(500),
    notes             TEXT,
    scheduled_date    TIMESTAMP,
    worker_ids        TEXT,
    worker_names      TEXT,
    start_time        TIME,
    end_time          TIME,
    visits_per_day    INTEGER,
    distance_km       DOUBLE PRECISION,
    duration_seconds  INTEGER,
    geometry          TEXT,
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW(),
    status            VARCHAR(255) DEFAULT 'pending'
);

ALTER TABLE visits ADD COLUMN IF NOT EXISTS client_label VARCHAR(500);
ALTER TABLE visits ADD COLUMN IF NOT EXISTS notes TEXT;
ALTER TABLE visits ADD COLUMN IF NOT EXISTS scheduled_date TIMESTAMP;
ALTER TABLE visits ADD COLUMN IF NOT EXISTS status VARCHAR(255) DEFAULT 'pending';

CREATE TABLE IF NOT EXISTS workers (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    phone            VARCHAR(20),
    area             VARCHAR(255),
    home_address     VARCHAR(500),
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);
