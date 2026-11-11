-- Review duplicates first; never delete historical rows automatically.
SELECT biz_type,biz_id,COUNT(*) FROM biz_stock_flow GROUP BY biz_type,biz_id HAVING COUNT(*)>1;
ALTER TABLE biz_stock_flow ADD UNIQUE KEY uk_stock_flow_event (biz_type,biz_id);
