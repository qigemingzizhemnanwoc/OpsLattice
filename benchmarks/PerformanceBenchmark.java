package org.example.back.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.example.back.common.result.PageResult;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.aspect.PreventDuplicateSubmitAspect;
import org.example.back.dto.*;
import org.example.back.vo.EmployeeVO;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit manual benchmark, outside Maven's automatic test source tree. */
public class PerformanceBenchmark {
    static final Path OUT=Path.of(System.getenv("BENCH_OUT"));
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final List<Object> results=new ArrayList<>();
    static final ThreadLocal<long[]> COUNTS=ThreadLocal.withInitial(()->new long[3]);
    @org.apache.ibatis.plugin.Intercepts({
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}),
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class})})
    public static class QueryCounts implements org.apache.ibatis.plugin.Interceptor {
        public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            Object result=call.proceed();long[] counts=COUNTS.get();
            if(result instanceof List<?> rows){counts[0]++;counts[1]+=rows.size();}else counts[2]++;
            return result;
        }
    }
    static void record(Object value) throws Exception {
        results.add(value);
        Files.writeString(OUT.resolve("samples.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        System.out.println(JSON.writeValueAsString(value));
    }
    @Configuration
    @Import({PurchaseService.class,org.example.back.controller.PurchaseController.class,AuthzService.class,
             EmployeeService.class,LegacyEmployeeBenchmarkService.class,SalesService.class})
    static class Config extends PurchaseStockFlowIntegrationTest.TestConfig {
        @Bean @Override DataSource dataSource() {
            int port=Integer.parseInt(System.getenv("BENCH_PORT"));
            assertNotEquals(3306,port,"Never connect to the host business database");
            var ds=new HikariDataSource();
            ds.setJdbcUrl("jdbc:mysql://127.0.0.1:"+port+"/warehouse_management?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&serverTimezone=Asia/Shanghai");
            ds.setUsername("root");ds.setPassword(System.getenv("BENCH_PASSWORD"));ds.setMaximumPoolSize(20);
            return ds;
        }
        @Bean MessageService messageService(){return mock(MessageService.class);}
        @Bean @Override org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource source,PurchaseStockFlowIntegrationTest.StockFlowInsertFault fault) throws Exception {
            var factory=super.sqlSessionFactory(source,fault);factory.getConfiguration().addInterceptor(new QueryCounts());return factory;
        }
    }
    public static void main(String[] args) throws Exception {
        record(Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),
                "processors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),
                "label",System.getenv("BENCH_LABEL")));
        if("redis".equals(System.getenv("BENCH_MODE"))){redis();return;}
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)){
            var user=new LoginResponse.UserInfoVO();user.setId(11L);user.setRole("superadmin");user.setRealName("Benchmark operator");
            when(ctx.getBean(AuthService.class).getUserInfo()).thenReturn(user);
            var db=new JdbcTemplate(ctx.getBean(DataSource.class));
            if("stock".equals(System.getenv("BENCH_MODE")))stock(ctx,db);else employee(ctx,db);
        }
    }
    static List<Long> ids(PageResult<EmployeeVO> p){return p.getRecords().stream().map(EmployeeVO::getId).toList();}
    static void employee(AnnotationConfigApplicationContext ctx,JdbcTemplate db) throws Exception {
        var old=ctx.getBean(LegacyEmployeeBenchmarkService.class);var current=ctx.getBean(EmployeeService.class);
        db.update("DELETE FROM sys_employee");db.update("DELETE FROM sys_user");
        // db.sql already seeds system_management on newer checkouts; keep the fixture idempotent.
        db.update("INSERT IGNORE INTO sys_dept(id,dept_code,dept_name,status) VALUES(900,'bench','Benchmark department',2),(901,'system_management','系统管理部',2)");
        db.update("INSERT INTO sys_user(id,username,password,real_name,role,dept_id,status,create_time) VALUES(11,'bench_admin','disabled','Benchmark admin','superadmin',900,1,'2024-01-01')");
        int previous=0;
        for(int n:new int[]{10000,50000,100000}){
            for(int start=previous;start<n;start+=1000){
                List<Object[]> users=new ArrayList<>(),employees=new ArrayList<>();
                for(int i=start;i<Math.min(start+1000,n);i++){
                    long id=1000L+i;String name="Synthetic employee "+i;
                    users.add(new Object[]{id,"bench_"+i,name,i%2});
                    employees.add(new Object[]{id,"BENCH_"+i,name,id,i%2});
                }
                db.batchUpdate("INSERT INTO sys_user(id,username,password,real_name,role,dept_id,status,create_time) VALUES(?,?,'disabled',?,'employee',900,?,'2024-01-01')",users);
                db.batchUpdate("INSERT INTO sys_employee(id,emp_code,emp_name,user_id,dept_id,position,status,create_time) VALUES(?,?,?,?,900,'普通员工',?,'2024-01-01')",employees);
            }
            previous=n;db.execute("ANALYZE TABLE sys_employee,sys_user");
            for(String scenario:List.of("first","filtered","deep")){
                var q=new EmployeeQueryDTO();q.setPageSize(20L);q.setPageNum(scenario.equals("deep")?(long)n/40:1L);
                if(scenario.equals("filtered")){q.setEmpName("employee 9");q.setDeptId(900L);q.setStatus(1);}
                // Measure exact historical page at 10k; at larger scales isolate pagination too.
                Supplier<PageResult<EmployeeVO>> full=()->old.page(q);
                Supplier<PageResult<EmployeeVO>> pagination=()->legacyPaginationOnly(old,q);
                var expected=pagination.get();var actual=current.page(q);
                assertEquals(expected.getTotal(),actual.getTotal());assertEquals(ids(expected),ids(actual));
                List<String> variants=new ArrayList<>(List.of("legacy_pagination_only","current"));
                if(n==10000 && scenario.equals("first"))variants.add("legacy_full");
                for(int repeat=-2;repeat<7;repeat++){
                    if(repeat%2==0)Collections.reverse(variants);
                    for(String variant:variants){
                        Supplier<PageResult<EmployeeVO>> call=variant.equals("current")?()->current.page(q):variant.equals("legacy_full")?full:pagination;
                        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
                        long allocated=threads.getThreadAllocatedBytes(Thread.currentThread().getId());
                        long heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                        COUNTS.set(new long[3]);long begin=System.nanoTime();var page=call.get();double ms=(System.nanoTime()-begin)/1e6;
                        long bytes=threads.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
                        assertEquals(expected.getTotal(),page.getTotal());assertEquals(ids(expected),ids(page));
                        if(repeat>=0){var sample=new LinkedHashMap<String,Object>();sample.put("kind","employee");sample.put("n",n);sample.put("scenario",scenario);sample.put("variant",variant);sample.put("repeat",repeat);
                            sample.put("ms",ms);sample.put("threadAllocatedBytes",bytes);sample.put("heapBeforeBytes",heap);sample.put("heapAfterBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());sample.put("returned",page.getRecords().size());
                            sample.put("sqlQueries",COUNTS.get()[0]);sample.put("sqlReturnedRows",COUNTS.get()[1]);sample.put("sqlWrites",COUNTS.get()[2]);record(sample);}
                    }
                }
                // Actual bound mapper SQL, including its filters; EXPLAIN runs outside latency samples.
                var factory=ctx.getBean(org.apache.ibatis.session.SqlSessionFactory.class);
                for(String method:List.of("directoryCount","directoryPage")){
                    var params=new HashMap<String,Object>();params.put("q",q);params.put("offset",(q.getPageNum()-1)*20);params.put("size",20L);
                    var bound=factory.getConfiguration().getMappedStatement("org.example.back.mapper.SysEmployeeMapper."+method).getBoundSql(params);
                    Object[] values=bound.getParameterMappings().stream().map(m->factory.getConfiguration().newMetaObject(params).getValue(m.getProperty())).toArray();
                    var plan=db.queryForList("EXPLAIN ANALYZE "+bound.getSql(),values);
                    Files.writeString(OUT.resolve("explain-"+n+"-"+scenario+"-"+method+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
                }
                var wrapper=new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<org.example.back.entity.SysEmployee>();
                wrapper.like(q.getEmpName()!=null,org.example.back.entity.SysEmployee::getEmpName,q.getEmpName())
                    .eq(q.getDeptId()!=null,org.example.back.entity.SysEmployee::getDeptId,q.getDeptId())
                    .eq(q.getStatus()!=null,org.example.back.entity.SysEmployee::getStatus,q.getStatus())
                    .orderByDesc(org.example.back.entity.SysEmployee::getCreateTime).orderByDesc(org.example.back.entity.SysEmployee::getId);
                var params=Map.<String,Object>of("ew",wrapper);
                var bound=factory.getConfiguration().getMappedStatement("org.example.back.mapper.SysEmployeeMapper.selectList").getBoundSql(params);
                Object[] values=bound.getParameterMappings().stream().map(m->factory.getConfiguration().newMetaObject(params).getValue(m.getProperty())).toArray();
                Files.writeString(OUT.resolve("explain-"+n+"-"+scenario+"-legacy-employee.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(db.queryForList("EXPLAIN ANALYZE "+bound.getSql(),values)));
            }
        }
    }
    @SuppressWarnings("unchecked")
    static PageResult<EmployeeVO> legacyPaginationOnly(LegacyEmployeeBenchmarkService old,EmployeeQueryDTO q){
        old=org.springframework.test.util.AopTestUtils.getTargetObject(old);
        // Exact historical private mapping/query helpers, without historical profile repair.
        Object dept=ReflectionTestUtils.invokeMethod(old,"resolveSystemManagementDept");
        List<EmployeeVO> rows=new ArrayList<>((List<EmployeeVO>)ReflectionTestUtils.invokeMethod(old,"listEmployeeRecords",q));
        rows.addAll((List<EmployeeVO>)ReflectionTestUtils.invokeMethod(old,"listManagementUserRecords",q,dept));
        rows.sort(Comparator.comparing(EmployeeVO::getCreateTime,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(EmployeeVO::getId,Comparator.nullsLast(Comparator.reverseOrder())));
        long total=rows.size();int from=(int)Math.min(total,(q.getPageNum()-1)*q.getPageSize()),to=(int)Math.min(total,from+q.getPageSize());
        return new PageResult<>(rows.subList(from,to),total,q.getPageNum(),q.getPageSize(),(total+q.getPageSize()-1)/q.getPageSize());
    }
    public static class Endpoint {
        AtomicInteger calls=new AtomicInteger();
        @PreventDuplicateSubmit(intervalMs=60000) public void submit(String value){calls.incrementAndGet();}
    }
    static void redis() throws Exception {
        var fingerprintAspect=new PreventDuplicateSubmitAspect();
        for(int repeat=-3;repeat<10;repeat++){
            var thread=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long allocated=thread.getThreadAllocatedBytes(Thread.currentThread().getId());long begin=System.nanoTime();String fingerprint=null;
            for(int i=0;i<100;i++)fingerprint=ReflectionTestUtils.invokeMethod(fingerprintAspect,"fingerprintArgs",(Object)new Object[]{Map.of("goodsId",123,"quantity",2),null,new int[]{1,2}});
            double ms=(System.nanoTime()-begin)/1e6;long bytes=thread.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
            if(repeat>=0)record(Map.of("kind","fingerprint","repeat",repeat,"calls",100,"batchMs",ms,"allocatedBytes",bytes,"fingerprint",fingerprint));
        }
        var cf=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv("BENCH_REDIS_PORT")));cf.afterPropertiesSet();cf.start();
        try{
            var redis=new StringRedisTemplate(cf);var target=new Endpoint();var aspect=new PreventDuplicateSubmitAspect();
            ReflectionTestUtils.setField(aspect,"stringRedisTemplate",redis);
            var factory=new AspectJProxyFactory(target);factory.addAspect(aspect);Endpoint proxy=factory.getProxy();
            // Register real Sa-Token web context support; unauthenticated same-IP requests measured here.
            cn.dev33.satoken.SaManager.setSaTokenContext(new cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet());
            for(int concurrency:new int[]{1,10,50,100}){
                var pool=Executors.newFixedThreadPool(concurrency);
                try{for(int repeat=-3;repeat<10;repeat++){
                    String key=UUID.randomUUID().toString();int before=target.calls.get();
                    var gate=new CyclicBarrier(concurrency+1);var times=new ArrayList<Future<double[]>>();
                    for(int i=0;i<concurrency;i++)times.add(pool.submit(()->{
                        var request=new MockHttpServletRequest("POST","/benchmark/submit");request.setRemoteAddr("127.0.0.1");
                        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));gate.await();
                        long begin=System.nanoTime();boolean admitted=true;
                        try{proxy.submit(key);}catch(BusinessException e){admitted=false;}finally{RequestContextHolder.resetRequestAttributes();}
                        return new double[]{(System.nanoTime()-begin)/1e6,admitted?1:0};
                    }));
                    long begin=System.nanoTime();gate.await();var latencies=new ArrayList<Double>();int accepted=0;
                    for(var f:times){var r=f.get(30,TimeUnit.SECONDS);latencies.add(r[0]);accepted+=(int)r[1];}
                    double seconds=(System.nanoTime()-begin)/1e9;assertEquals(1,accepted);assertEquals(1,target.calls.get()-before);
                    if(repeat>=0)record(Map.of("kind","redis","concurrency",concurrency,"repeat",repeat,"admitted",accepted,"rejected",concurrency-accepted,"actualCalls",target.calls.get()-before,"latencyMs",latencies,"batchSeconds",seconds,"throughput",concurrency/seconds));
                }}finally{pool.shutdownNow();}
            }
        }finally{cf.destroy();}
    }
    static void stock(AnnotationConfigApplicationContext ctx,JdbcTemplate db) throws Exception {
        var sales=ctx.getBean(SalesService.class);
        for(int repeat=0;repeat<5;repeat++){
            String key="BENCH"+UUID.randomUUID().toString().substring(0,12);
            db.update("INSERT INTO base_goods(goods_code,goods_name,supplier_id,stock,status,purchase_price,sale_price) VALUES(?,?,1,5,1,1,2)",key,key);
            Long id=db.queryForObject("SELECT id FROM base_goods WHERE goods_code=?",Long.class,key);
            var pool=Executors.newFixedThreadPool(10);var gate=new CyclicBarrier(10);int success=0;
            try{var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<10;i++)futures.add(pool.submit(()->{
                gate.await();var dto=new SalesSaveDTO();dto.setGoodsId(id);dto.setQuantity(1);
                try{sales.create(dto);return true;}catch(BusinessException e){return false;}
            }));for(var f:futures)if(f.get(30,TimeUnit.SECONDS))success++;}finally{pool.shutdownNow();}
            int stock=db.queryForObject("SELECT stock FROM base_goods WHERE id=?",Integer.class,id);
            int orders=db.queryForObject("SELECT COUNT(*) FROM biz_sales WHERE goods_id=?",Integer.class,id);
            int flows=db.queryForObject("SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id=?",Integer.class,id);
            int delta=db.queryForObject("SELECT SUM(change_quantity) FROM biz_stock_flow WHERE goods_id=?",Integer.class,id);
            assertEquals(5,success);assertEquals(0,stock);assertEquals(5,orders);assertEquals(5,flows);assertEquals(-5,delta);
            record(Map.of("kind","stock","repeat",repeat,"requests",10,"success",success,"rejected",10-success,"finalStock",stock,"orders",orders,"flows",flows,"delta",delta));
        }
    }
}
