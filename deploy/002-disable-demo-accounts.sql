-- Fresh Compose volume only, after db.sql. Never run against an existing business database.
USE warehouse_management;
UPDATE sys_user SET status = 0, password = '';
