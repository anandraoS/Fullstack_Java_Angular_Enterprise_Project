-- Synthetic demo data (profile `dev` only; contract section 9). No real people or domains.
INSERT INTO customers (id, name, email, tier, created_at) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'Acme Industrial Supply', 'procurement@acme.example.test', 'GOLD',     TIMESTAMP WITH TIME ZONE '2026-01-05 09:00:00+00'),
    ('c0000000-0000-0000-0000-000000000002', 'Globex Logistics',       'orders@globex.example.test',      'SILVER',   TIMESTAMP WITH TIME ZONE '2026-01-06 09:30:00+00'),
    ('c0000000-0000-0000-0000-000000000003', 'Initech Failures',       'ops@fail.example.test',           'STANDARD', TIMESTAMP WITH TIME ZONE '2026-01-07 10:00:00+00');
