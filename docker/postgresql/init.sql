-- Creates one database per service. Since the split there is no shared schema:
-- catalog-service owns video_catalog and membership-service owns video_membership,
-- and neither can reach the other's tables with a JOIN.
--
-- IMPORTANT: PostgreSQL runs /docker-entrypoint-initdb.d/ only when the data directory
-- is empty. On an existing volume this file is ignored without any warning. To force it:
--   docker compose -f docker/services.yaml down -v
--   docker compose -f docker/services.yaml up -d
--
-- Each service still creates its own tables at startup (spring.jpa.hibernate.ddl-auto=update).
CREATE DATABASE video_catalog;
CREATE DATABASE video_membership;
