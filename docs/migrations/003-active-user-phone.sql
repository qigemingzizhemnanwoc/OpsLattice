-- Review active duplicates first. Do not delete or rewrite historical rows.
SELECT TRIM(phone), COUNT(*) FROM sys_user
WHERE is_deleted=0 AND phone IS NOT NULL AND TRIM(phone)<>''
GROUP BY TRIM(phone) HAVING COUNT(*)>1;
-- Apply only after duplicate review; existing username uniqueness is preserved.
ALTER TABLE sys_user
 ADD COLUMN active_phone VARCHAR(20) GENERATED ALWAYS AS
 (CASE WHEN is_deleted=0 THEN NULLIF(TRIM(phone),'') ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_active_phone(active_phone);
