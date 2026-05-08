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

