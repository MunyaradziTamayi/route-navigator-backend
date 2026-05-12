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

CREATE INDEX IF NOT EXISTS idx_routes_coordinates
    ON routes (origin_lat, origin_lon, destination_lat, destination_lon, created_at DESC);

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
    staff_number     VARCHAR(100),
    name             VARCHAR(255) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    phone            VARCHAR(20),
    area             VARCHAR(255),
    home_address     VARCHAR(500),
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

ALTER TABLE workers ADD COLUMN IF NOT EXISTS staff_number VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS idx_workers_staff_number
    ON workers (staff_number);

CREATE TABLE IF NOT EXISTS worker_mileage_uploads (
    id             BIGSERIAL PRIMARY KEY,
    file_name      VARCHAR(255) NOT NULL,
    created_at     TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS worker_mileage_reports (
    id             BIGSERIAL PRIMARY KEY,
    upload_id      BIGINT REFERENCES worker_mileage_uploads(id) ON DELETE SET NULL,
    report_month   VARCHAR(7) NOT NULL,
    file_name      VARCHAR(255) NOT NULL,
    file_content   BYTEA NOT NULL,
    created_at     TIMESTAMP DEFAULT NOW()
);

ALTER TABLE worker_mileage_reports ADD COLUMN IF NOT EXISTS upload_id BIGINT REFERENCES worker_mileage_uploads(id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS worker_mileage_report_rows (
    id              BIGSERIAL PRIMARY KEY,
    report_id       BIGINT NOT NULL REFERENCES worker_mileage_reports(id) ON DELETE CASCADE,
    staff_number    VARCHAR(100) NOT NULL,
    total_mileage   DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS worker_mileage_visits (
    id              BIGSERIAL PRIMARY KEY,
    report_id       BIGINT REFERENCES worker_mileage_reports(id) ON DELETE CASCADE,
    staff_number    VARCHAR(100) NOT NULL,
    visit_date      DATE NOT NULL,
    start_time      TIME,
    end_time        TIME,
    address         VARCHAR(1000) NOT NULL,
    lat             DOUBLE PRECISION,
    lon             DOUBLE PRECISION,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_worker_mileage_reports_month
    ON worker_mileage_reports (report_month, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_worker_mileage_uploads_created_at
    ON worker_mileage_uploads (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_worker_mileage_reports_upload_id
    ON worker_mileage_reports (upload_id, report_month);

CREATE INDEX IF NOT EXISTS idx_worker_mileage_report_rows_report_id
    ON worker_mileage_report_rows (report_id, staff_number);

CREATE INDEX IF NOT EXISTS idx_worker_mileage_visits_staff_number
    ON worker_mileage_visits (staff_number, visit_date DESC, start_time DESC);

CREATE INDEX IF NOT EXISTS idx_worker_mileage_visits_report_id
    ON worker_mileage_visits (report_id, staff_number, visit_date, start_time);

CREATE TABLE IF NOT EXISTS app_users (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_users_email
    ON app_users (email);
