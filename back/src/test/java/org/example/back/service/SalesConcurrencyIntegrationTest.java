package org.example.back.service;
import org.example.back.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
@SpringJUnitConfig(PurchaseStockFlowIntegrationTest.TestConfig.class)
@Import({SalesService.class, PurchaseReturnService.class, SalesReturnService.class})
class SalesConcurrencyIntegrationTest {
 @Autowired DataSource source;
 @Autowired SalesService sales;
 @Autowired PurchaseService purchases;
 @Autowired PurchaseStockFlowIntegrationTest.StockFlowInsertFault fault;
 @Autowired AuthService auth;
 @Autowired PurchaseReturnService purchaseReturns;
 @Autowired SalesReturnService salesReturns;
 @Test void concurrentDeleteRestoresExactlyOnce() throws Exception {
  var db=new JdbcTemplate(source);String marker="SC"+UUID.randomUUID().toString().replace("-","").substring(0,16);
  db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status) VALUES(?,?,1,10,1)",marker,marker);
  Long goods=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,marker);
  db.update("INSERT INTO biz_sales(sales_no,goods_id,quantity,unit_price,total_price,biz_status,operation_time) VALUES(?,?,3,1,3,1,NOW())",marker,goods);
  Long id=db.queryForObject("SELECT id FROM biz_sales WHERE sales_no=?",Long.class,marker);
  var user=new LoginResponse.UserInfoVO();user.setId(900001L);user.setRole("admin");user.setDeptCode("sales");when(auth.getUserInfo()).thenReturn(user);
  var gate=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
  Callable<Boolean> task=()->{gate.await(10,TimeUnit.SECONDS);try{sales.delete(id);return true;}catch(org.example.back.common.exception.BusinessException ex){return false;}};
  try {var a=pool.submit(task);var b=pool.submit(task);int successes=(a.get(20,TimeUnit.SECONDS)?1:0)+(b.get(20,TimeUnit.SECONDS)?1:0);assertEquals(1,successes);assertEquals(13,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));assertEquals(1,db.queryForObject("SELECT is_deleted FROM biz_sales WHERE id=?",Integer.class,id));} finally {pool.shutdownNow();}
 }

 @Test void concurrentSalesCannotOversellAndLosingOrdersRollback() throws Exception {
  var db=new JdbcTemplate(source);String marker="OS"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
  db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status,purchase_price,sale_price) VALUES(?,?,1,5,1,1,2)",marker,marker);
  Long goods=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,marker);
  var user=new LoginResponse.UserInfoVO();user.setId(900001L);user.setRealName("test");user.setRole("admin");user.setDeptCode("sales");when(auth.getUserInfo()).thenReturn(user);
  var gate=new CyclicBarrier(8);var pool=Executors.newFixedThreadPool(8);
  try {
   var futures=new java.util.ArrayList<Future<Boolean>>();
   for(int i=0;i<8;i++) futures.add(pool.submit(()->{gate.await(15,TimeUnit.SECONDS);var dto=new org.example.back.dto.SalesSaveDTO();dto.setGoodsId(goods);dto.setQuantity(1);try{sales.create(dto);return true;}catch(org.example.back.common.exception.BusinessException e){return false;}}));
   int success=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))success++;
   assertEquals(5,success);assertEquals(0,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));
   assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM biz_sales WHERE goods_id=?",Integer.class,goods));
  } finally {pool.shutdownNow();}
 }
 @Test void failedInventoryRestoreRollsBackLogicalDelete() {
  var db=new JdbcTemplate(source);String marker="RB"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
  db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status,is_deleted) VALUES(?,?,1,0,1,1)",marker,marker);
  Long goods=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,marker);
  db.update("INSERT INTO biz_sales(sales_no,goods_id,quantity,unit_price,total_price,biz_status,operation_time) VALUES(?,?,1,1,1,1,NOW())",marker,goods);
  Long id=db.queryForObject("SELECT id FROM biz_sales WHERE sales_no=?",Long.class,marker);
  var user=new LoginResponse.UserInfoVO();user.setId(900001L);user.setRole("admin");user.setDeptCode("sales");when(auth.getUserInfo()).thenReturn(user);
  assertThrows(org.example.back.common.exception.BusinessException.class,()->sales.delete(id));
  assertEquals(0,db.queryForObject("SELECT is_deleted FROM biz_sales WHERE id=?",Integer.class,id));
  assertEquals(0,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));
 }

 @Test void concurrentPurchaseReturnsRespectSourceQuantity() throws Exception {
  var db=new JdbcTemplate(source);String marker="RT"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
  db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status,purchase_price,sale_price) VALUES(?,?,1,10,1,1,2)",marker,marker);
  Long goods=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,marker);
  db.update("INSERT INTO biz_purchase(purchase_no,goods_id,quantity,unit_price,total_price,biz_status,operation_time) VALUES(?,?,5,1,5,1,NOW())",marker,goods);
  Long id=db.queryForObject("SELECT id FROM biz_purchase WHERE purchase_no=?",Long.class,marker);
  var user=new LoginResponse.UserInfoVO();user.setId(900001L);user.setRealName("test");user.setRole("admin");user.setDeptCode("purchase");when(auth.getUserInfo()).thenReturn(user);
  var gate=new CyclicBarrier(4);var pool=Executors.newFixedThreadPool(4);
  try {var futures=new java.util.ArrayList<Future<Boolean>>();
   for(int i=0;i<4;i++)futures.add(pool.submit(()->{gate.await(15,TimeUnit.SECONDS);var dto=new org.example.back.dto.PurchaseReturnSaveDTO();dto.setSourcePurchaseId(id);dto.setQuantity(2);try{purchaseReturns.create(dto);return true;}catch(org.example.back.common.exception.BusinessException e){return false;}}));
   int success=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))success++;
   assertEquals(2,success);assertEquals(6,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));
   assertEquals(4,db.queryForObject("SELECT SUM(quantity) FROM biz_purchase_return WHERE source_purchase_id=?",Integer.class,id));
  }finally{pool.shutdownNow();}
 }

 @Test void concurrentSalesReturnsRespectSourceQuantity() throws Exception {
  var db=new JdbcTemplate(source);String marker="RT"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
  db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status,purchase_price,sale_price) VALUES(?,?,1,10,1,1,2)",marker,marker);
  Long goods=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,marker);
  db.update("INSERT INTO biz_sales(sales_no,goods_id,quantity,unit_price,total_price,biz_status,operation_time) VALUES(?,?,5,1,5,1,NOW())",marker,goods);
  Long id=db.queryForObject("SELECT id FROM biz_sales WHERE sales_no=?",Long.class,marker);
  var user=new LoginResponse.UserInfoVO();user.setId(900001L);user.setRealName("test");user.setRole("admin");user.setDeptCode("sales");when(auth.getUserInfo()).thenReturn(user);
  var gate=new CyclicBarrier(4);var pool=Executors.newFixedThreadPool(4);
  try {var futures=new java.util.ArrayList<Future<Boolean>>();
   for(int i=0;i<4;i++)futures.add(pool.submit(()->{gate.await(15,TimeUnit.SECONDS);var dto=new org.example.back.dto.SalesReturnSaveDTO();dto.setSourceSalesId(id);dto.setQuantity(2);try{salesReturns.create(dto);return true;}catch(org.example.back.common.exception.BusinessException e){return false;}}));
   int success=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))success++;
   assertEquals(2,success);assertEquals(14,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));
   assertEquals(4,db.queryForObject("SELECT SUM(quantity) FROM biz_sales_return WHERE source_sales_id=?",Integer.class,id));
  }finally{pool.shutdownNow();}
 }

 @Test void remainingDeletesHaveSingleFlow() throws Exception {
  for(String kind:java.util.List.of("purchase","purchase_return","sales_return")) {
   var db=new JdbcTemplate(source);String marker="DC"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
   db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status) VALUES(?,?,1,10,1)",marker,marker);
   Long goods=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,marker);
   String number=kind.equals("purchase")?"purchase_no":"return_no";
   db.update("INSERT INTO biz_"+kind+"("+number+",goods_id,quantity,unit_price,total_price,biz_status,operation_time) VALUES(?,?,2,1,2,1,NOW())",marker,goods);
   Long id=db.queryForObject("SELECT id FROM biz_"+kind+" WHERE "+number+"=?",Long.class,marker);
   var user=new LoginResponse.UserInfoVO();user.setId(900001L);user.setRole("superadmin");when(auth.getUserInfo()).thenReturn(user);
   Runnable action=()->{switch(kind){case "purchase"->purchases.delete(id);case "purchase_return"->purchaseReturns.delete(id);default->salesReturns.delete(id);}};
   var pool=Executors.newFixedThreadPool(2);var gate=new CyclicBarrier(2);
   Callable<Boolean> task=()->{gate.await(10,TimeUnit.SECONDS);try{action.run();return true;}catch(org.example.back.common.exception.BusinessException e){return false;}};
   try{var a=pool.submit(task);var b=pool.submit(task);assertEquals(1,(a.get(20,TimeUnit.SECONDS)?1:0)+(b.get(20,TimeUnit.SECONDS)?1:0));}finally{pool.shutdownNow();}
   int delta=kind.equals("purchase_return")?2:-2;
   assertEquals(10+delta,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id=?",Integer.class,goods));
   assertEquals(delta,db.queryForObject("SELECT SUM(change_quantity) FROM biz_stock_flow WHERE goods_id=?",Integer.class,goods));
  }
 }

 @Test void sourceVoidRacesReturnAndRepeatedVoidHasNoExtraEffect() throws Exception {
  var db=new JdbcTemplate(source);String marker="VR"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
  db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status,purchase_price,sale_price) VALUES(?,?,1,10,1,1,2)",marker,marker);
  Long goods=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,marker);
  db.update("INSERT INTO biz_sales(sales_no,goods_id,quantity,unit_price,total_price,biz_status,operation_time) VALUES(?,?,5,1,5,1,NOW())",marker,goods);
  Long id=db.queryForObject("SELECT id FROM biz_sales WHERE sales_no=?",Long.class,marker);
  var user=new LoginResponse.UserInfoVO();user.setId(900001L);user.setRole("superadmin");when(auth.getUserInfo()).thenReturn(user);
  var pool=Executors.newFixedThreadPool(2);var gate=new CyclicBarrier(2);
  try{
   var a=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);try{sales.voidDocument(id,null);return true;}catch(org.example.back.common.exception.BusinessException e){return false;}});
   var b=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);var dto=new org.example.back.dto.SalesReturnSaveDTO();dto.setSourceSalesId(id);dto.setQuantity(2);try{salesReturns.create(dto);return true;}catch(org.example.back.common.exception.BusinessException e){return false;}});
   boolean cancelled=a.get(20,TimeUnit.SECONDS);boolean returned=b.get(20,TimeUnit.SECONDS);assertNotEquals(cancelled,returned);
   assertThrows(org.example.back.common.exception.BusinessException.class,()->sales.voidDocument(id,null));
   assertEquals(cancelled?15:12,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id=?",Integer.class,goods));
   assertEquals(cancelled?5:2,db.queryForObject("SELECT SUM(change_quantity) FROM biz_stock_flow WHERE goods_id=?",Integer.class,goods));
  }finally{pool.shutdownNow();}
 }

 @Test void flowFailureRollsBackSaleAndAllReversalsReconcile() {
  var db=new JdbcTemplate(source);String marker="LC"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
  db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status,purchase_price,sale_price) VALUES(?,?,1,10,1,1,2)",marker,marker);
  Long goods=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,marker);
  var user=new LoginResponse.UserInfoVO();user.setId(900001L);user.setRole("superadmin");when(auth.getUserInfo()).thenReturn(user);
  var sd=new org.example.back.dto.SalesSaveDTO();sd.setGoodsId(goods);sd.setQuantity(2);
  org.springframework.test.util.ReflectionTestUtils.setField(fault,"goodsId",goods);
  try{assertThrows(org.example.back.common.exception.BusinessException.class,()->sales.create(sd));}finally{org.springframework.test.util.ReflectionTestUtils.setField(fault,"goodsId",null);}
  assertEquals(10,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM biz_sales WHERE goods_id=?",Integer.class,goods));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id=?",Integer.class,goods));
  var pd=new org.example.back.dto.PurchaseSaveDTO();pd.setGoodsId(goods);pd.setQuantity(4);purchases.create(pd);
  Long pid=db.queryForObject("SELECT id FROM biz_purchase WHERE goods_id=?",Long.class,goods);
  var pr=new org.example.back.dto.PurchaseReturnSaveDTO();pr.setSourcePurchaseId(pid);pr.setQuantity(1);purchaseReturns.create(pr);
  Long prid=db.queryForObject("SELECT id FROM biz_purchase_return WHERE goods_id=?",Long.class,goods);
  assertThrows(org.example.back.common.exception.BusinessException.class,()->purchases.voidDocument(pid,null));
  purchaseReturns.voidDocument(prid,null);assertThrows(org.example.back.common.exception.BusinessException.class,()->purchaseReturns.voidDocument(prid,null));
  purchases.voidDocument(pid,null);assertThrows(org.example.back.common.exception.BusinessException.class,()->purchases.voidDocument(pid,null));
  sales.create(sd);Long sid=db.queryForObject("SELECT id FROM biz_sales WHERE goods_id=?",Long.class,goods);
  var sr=new org.example.back.dto.SalesReturnSaveDTO();sr.setSourceSalesId(sid);sr.setQuantity(1);salesReturns.create(sr);
  Long srid=db.queryForObject("SELECT id FROM biz_sales_return WHERE goods_id=?",Long.class,goods);
  assertThrows(org.example.back.common.exception.BusinessException.class,()->sales.delete(sid));
  salesReturns.voidDocument(srid,null);assertThrows(org.example.back.common.exception.BusinessException.class,()->salesReturns.voidDocument(srid,null));
  sales.voidDocument(sid,null);assertThrows(org.example.back.common.exception.BusinessException.class,()->sales.voidDocument(sid,null));
  assertEquals(10,db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,goods));
  assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id=?",Integer.class,goods));
  assertEquals(0,db.queryForObject("SELECT SUM(change_quantity) FROM biz_stock_flow WHERE goods_id=?",Integer.class,goods));
  int previous=10;for(var row:db.queryForList("SELECT before_stock,after_stock,change_quantity FROM biz_stock_flow WHERE goods_id=? ORDER BY id",goods)){assertEquals(previous,((Number)row.get("before_stock")).intValue());previous+=((Number)row.get("change_quantity")).intValue();assertEquals(previous,((Number)row.get("after_stock")).intValue());}
 }
}
