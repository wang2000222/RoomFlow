package com.roomflow;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:roomflowtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class BookingTest {
 @Autowired RoomService service; @Autowired DemoAuth auth; @Autowired JdbcTemplate db;
 DemoAuth.Actor user,other,manager,owner; long start;
 @BeforeEach void reset(){db.update("delete from audit");db.update("delete from booking");db.update("update room set state='READY'");user=actor("guest");other=actor("guest2");manager=actor("manager");owner=actor("owner");start=System.currentTimeMillis()+120000;}
 DemoAuth.Actor actor(String account){Map<String,Object> session=auth.login(account);return auth.require("Bearer "+session.get("token"));}
 Map<String,Object> reserve(DemoAuth.Actor actor,long room,String key){return service.reserve(actor,room,start,start+3600000,key);}
 String id(Map<String,Object> b){return (String)b.get("id");}
 void code(String expected,Runnable action){ApiProblem error=assertThrows(ApiProblem.class,action::run);assertEquals(expected,error.code);}
 @Test void endToEndAndCleaning(){String id=id(reserve(user,1,"flow_0001"));service.action(user,id,"pay");service.action(user,id,"pay");service.action(user,id,"open");assertEquals("IN_USE",service.booking(id).get("state"));service.action(user,id,"finish");assertEquals("CLEANING",service.rooms().get(0).get("state"));code("ROOM_UNAVAILABLE",()->reserve(other,1,"flow_0002"));service.roomState(manager,1,"READY");assertEquals("COMPLETED",service.booking(id).get("state"));assertFalse(service.audits(owner).isEmpty());}
 @Test void overlappingAndAdjacent(){reserve(user,1,"overlap01");code("SLOT_CONFLICT",()->reserve(other,1,"overlap02"));assertNotNull(service.reserve(other,1,start+3600000,start+7200000,"adjacent1"));}
 @Test void ownOrdersAndStorePermissions(){String id=id(reserve(user,3,"scope_001"));assertTrue(service.bookings(other).isEmpty());assertTrue(service.bookings(manager).isEmpty());code("FORBIDDEN",()->service.action(other,id,"cancel"));code("FORBIDDEN",()->service.action(manager,id,"cancel"));code("FORBIDDEN",()->service.roomState(user,1,"READY"));code("FORBIDDEN",()->service.roomState(manager,3,"MAINTENANCE"));}
 @Test void idempotencyAndChangedInput(){String first=id(reserve(user,1,"request01"));assertEquals(first,id(reserve(user,1,"request01")));code("KEY_REUSED",()->service.reserve(user,1,start,start+7200000,"request01"));}
 @Test void invalidStateAndWindow(){String id=id(service.reserve(user,1,start+3600000,start+7200000,"window001"));code("STATE_INVALID",()->service.action(user,id,"open"));service.action(user,id,"pay");code("OUTSIDE_WINDOW",()->service.action(user,id,"open"));service.action(user,id,"cancel");code("STATE_INVALID",()->service.action(user,id,"pay"));}
 @Test void pendingExpiryReleasesSlot(){String id=id(reserve(user,1,"expiry001"));db.update("update booking set created_at=? where id=?",System.currentTimeMillis()-16*60000,id);reserve(other,1,"expiry002");assertEquals("EXPIRED",service.booking(id).get("state"));}
 @Test void concurrentBookingOnlyOneSucceeds() throws Exception {ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch latch=new CountDownLatch(1);try{List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++){final int j=i;results.add(pool.submit(()->{latch.await();try{reserve(j==0?user:other,1,"concurrent"+j);return true;}catch(ApiProblem e){assertEquals("SLOT_CONFLICT",e.code);return false;}}));}latch.countDown();int successes=0;for(Future<Boolean> result:results)if(result.get(10,TimeUnit.SECONDS))successes++;assertEquals(1,successes);}finally{pool.shutdownNow();}}
 @Test void demoDisabledFailsClosed(){DemoAuth production=new DemoAuth(false);code("PROVIDER_REQUIRED",()->production.login("owner"));code("PROVIDER_REQUIRED",production::requireDemo);}
 @Test void authAndDurationValidation(){code("UNAUTHORIZED",()->auth.require(null));code("TIME_INVALID",()->service.reserve(user,1,start,start+600000,"invalid01"));}
}
