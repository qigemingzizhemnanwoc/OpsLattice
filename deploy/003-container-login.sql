-- Container-only policy: the loopback-published Nginx is the sole ingress.
-- Keep this address synchronized with compose.yaml. Do not apply to business databases.
USE warehouse_management;
INSERT INTO sys_ip_policy (policy_name, ip_cidr, allow_flag, status, priority, remark)
SELECT 'Compose local Nginx', '172.30.94.10/32', 1, 1, 2, 'Local-only container ingress; not a public client IP policy'
WHERE NOT EXISTS (SELECT 1 FROM sys_ip_policy WHERE policy_name='Compose local Nginx');
