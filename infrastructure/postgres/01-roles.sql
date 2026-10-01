\getenv sentinel_app_password SENTINEL_DB_APP_PASSWORD
CREATE ROLE sentinel_app LOGIN PASSWORD :'sentinel_app_password';
REVOKE CONNECT ON DATABASE sentinel FROM PUBLIC;
GRANT CONNECT ON DATABASE sentinel TO sentinel_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
