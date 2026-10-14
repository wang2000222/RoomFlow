package com.roomflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:roomflowtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "roomflow.jobs-enabled=false"
    })
@AutoConfigureMockMvc
class BookingTest {
  @Autowired RoomService service;
  @Autowired DemoAuth auth;
  @Autowired JdbcTemplate db;
  DemoAuth.Actor user, other, manager, owner;
  long start;
  @Autowired OperationsService ops;
  @Autowired WorkflowService workflow;
  @Autowired OperationsJobs jobs;
  @Autowired StaffService staff;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;

  @BeforeEach
  void reset() {
    for (String table :
        Arrays.asList(
            "audit",
            "notification",
            "simulation_fault",
            "device_command",
            "refund_record",
            "payment_record",
            "extension_order",
            "booking",
            "price_package",
            "staff_account")) db.update("delete from " + table);
    db.update("delete from room where id>=100");
    db.update("delete from store where id>=100");
    db.update(
        "update store set open_minute=0,close_minute=1440,cleaning_minutes=15,early_minutes=0,cancel_minutes=60,enabled=true");
    db.update("update room set state='READY',enabled=true");
    user = actor("guest");
    other = actor("guest2");
    manager = actor("manager");
    owner = actor("owner");
    start = System.currentTimeMillis() + 120000;
  }

  DemoAuth.Actor actor(String account) {
    Map<String, Object> session = auth.login(account);
    return auth.require("Bearer " + session.get("token"));
  }

  Map<String, Object> reserve(DemoAuth.Actor actor, long room, String key) {
    return service.reserve(actor, room, start, start + 3600000, key);
  }

  String id(Map<String, Object> b) {
    return (String) b.get("id");
  }

  void code(String expected, Runnable action) {
    ApiProblem error = assertThrows(ApiProblem.class, action::run);
    assertEquals(expected, error.code);
  }

  @Test
  void endToEndAndCleaning() {
    start = System.currentTimeMillis() - 1000;
    String id = id(reserve(user, 1, "flow_0001"));
    service.action(user, id, "pay");
    service.action(user, id, "pay");
    service.action(user, id, "open");
    assertEquals("IN_USE", service.booking(id).get("state"));
    service.action(user, id, "finish");
    assertEquals("CLEANING", service.rooms().get(0).get("state"));
    code("ROOM_UNAVAILABLE", () -> reserve(other, 1, "flow_0002"));
    service.roomState(manager, 1, "READY");
    assertEquals("COMPLETED", service.booking(id).get("state"));
    assertFalse(service.audits(owner).isEmpty());
  }

  @Test
  void overlappingAndAdjacent() {
    reserve(user, 1, "overlap01");
    code("SLOT_CONFLICT", () -> reserve(other, 1, "overlap02"));
    assertNotNull(service.reserve(other, 1, start + 4500000, start + 8100000, "adjacent1"));
  }

  @Test
  void ownOrdersAndStorePermissions() {
    String id = id(reserve(user, 3, "scope_001"));
    assertTrue(service.bookings(other).isEmpty());
    assertTrue(service.bookings(manager).isEmpty());
    code("FORBIDDEN", () -> service.action(other, id, "cancel"));
    code("FORBIDDEN", () -> service.action(manager, id, "cancel"));
    code("FORBIDDEN", () -> service.roomState(user, 1, "READY"));
    code("FORBIDDEN", () -> service.roomState(manager, 3, "MAINTENANCE"));
  }

  @Test
  void idempotencyAndChangedInput() {
    String first = id(reserve(user, 1, "request01"));
    assertEquals(first, id(reserve(user, 1, "request01")));
    code("KEY_REUSED", () -> service.reserve(user, 1, start, start + 7200000, "request01"));
  }

  @Test
  void invalidStateAndWindow() {
    String id = id(service.reserve(user, 1, start + 3600000, start + 7200000, "window001"));
    code("STATE_INVALID", () -> service.action(user, id, "open"));
    service.action(user, id, "pay");
    code("OUTSIDE_WINDOW", () -> service.action(user, id, "open"));
    service.action(owner, id, "cancel");
    code("STATE_INVALID", () -> service.action(user, id, "pay"));
  }

  @Test
  void pendingExpiryReleasesSlot() {
    String id = id(reserve(user, 1, "expiry001"));
    db.update(
        "update booking set created_at=? where id=?", System.currentTimeMillis() - 16 * 60000, id);
    reserve(other, 1, "expiry002");
    assertEquals("EXPIRED", service.booking(id).get("state"));
  }

  @Test
  void concurrentBookingOnlyOneSucceeds() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch latch = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        final int j = i;
        results.add(
            pool.submit(
                () -> {
                  latch.await();
                  try {
                    reserve(j == 0 ? user : other, 1, "concurrent" + j);
                    return true;
                  } catch (ApiProblem e) {
                    assertEquals("SLOT_CONFLICT", e.code);
                    return false;
                  }
                }));
      }
      latch.countDown();
      int successes = 0;
      for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void demoDisabledFailsClosed() {
    DemoAuth production = new DemoAuth(false);
    code("PROVIDER_REQUIRED", () -> production.login("owner"));
    code("PROVIDER_REQUIRED", production::requireDemo);
  }

  @Test
  void authAndDurationValidation() {
    code("UNAUTHORIZED", () -> auth.require(null));
    code("TIME_INVALID", () -> service.reserve(user, 1, start, start + 600000, "invalid01"));
  }

  Map<String, Object> map(Object... args) {
    Map<String, Object> b = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i += 2) b.put((String) args[i], args[i + 1]);
    return b;
  }

  @Test
  void cleaningBufferBlocksAdjacent() {
    reserve(user, 1, "buffer001");
    code(
        "SLOT_CONFLICT",
        () -> service.reserve(other, 1, start + 3600000, start + 7200000, "buffer002"));
  }

  @Test
  void earlyEntryBufferBlocksPreviousSlot() {
    db.update("update store set early_minutes=10 where id=1");
    reserve(user, 1, "early0001");
    code(
        "SLOT_CONFLICT",
        () -> service.reserve(other, 1, start + 4500000, start + 8100000, "early0002"));
  }

  @Test
  void packagePricingAndSnapshot() {
    Map<String, Object> p =
        (Map<String, Object>)
            ops.savePackage(
                owner, map("roomId", 1, "name", "两小时畅玩", "minutes", 120, "amountCents", 6000));
    String id =
        id(
            service.reservePackage(
                user, 1, start, start + 7200000, "package01", (String) p.get("id")));
    assertEquals(6000L, service.number(service.booking(id), "amount_cents"));
    db.update("update room set price_cents=9900 where id=1");
    assertEquals(3800L, service.number(service.booking(id), "hourly_cents"));
    db.update("update room set price_cents=3800 where id=1");
  }

  @Test
  void extensionHoldsSlotAndPaymentIdempotent() {
    String id = id(reserve(user, 1, "extend001"));
    service.action(user, id, "pay");
    Map<String, Object> e = (Map<String, Object>) service.extend(user, id, 60, "extension1");
    code(
        "SLOT_CONFLICT",
        () -> service.reserve(other, 1, start + 4500000, start + 8100000, "extend002"));
    service.payExtension(user, (String) e.get("id"));
    service.payExtension(user, (String) e.get("id"));
    assertEquals(start + 7200000, service.number(service.booking(id), "ends_at"));
    assertEquals(7600L, service.number(service.booking(id), "amount_cents"));
    assertEquals(2, db.queryForObject("select count(*) from payment_record", Integer.class));
  }

  @Test
  void extensionCannotOverlapNextBooking() {
    String id = id(reserve(user, 1, "next00001"));
    service.reserve(other, 1, start + 4500000, start + 8100000, "next00002");
    service.action(user, id, "pay");
    code("SLOT_CONFLICT", () -> service.extend(user, id, 60, "extendnext"));
  }

  @Test
  void refundFailureAndRetryNotFalseSuccess() {
    start = System.currentTimeMillis() + 7200000;
    String id = id(reserve(user, 1, "refund001"));
    service.action(user, id, "pay");
    workflow.fault(owner, "refund:" + id, 1);
    service.action(user, id, "cancel");
    Map<String, Object> r = db.queryForMap("select * from refund_record where booking_id=?", id);
    assertEquals("FAILED", r.get("state"));
    assertEquals("CANCELLED", service.booking(id).get("state"));
    workflow.retryRefund(manager, (String) r.get("id"));
    assertEquals(
        "SUCCESS",
        db.queryForObject("select state from refund_record where booking_id=?", String.class, id));
    workflow.retryRefund(manager, (String) r.get("id"));
    assertEquals(
        2,
        db.queryForObject(
            "select attempts from refund_record where booking_id=?", Integer.class, id));
  }

  @Test
  void deviceFailureRemainsPaidUntilAcknowledged() {
    start = System.currentTimeMillis() - 1000;
    String id = id(reserve(user, 1, "device001"));
    service.action(user, id, "pay");
    workflow.fault(owner, "device:1:OPEN", 1);
    service.action(user, id, "open");
    assertEquals("PAID", service.booking(id).get("state"));
    assertEquals("READY", service.rooms().get(0).get("state"));
    service.action(user, id, "open");
    assertEquals("IN_USE", service.booking(id).get("state"));
    assertEquals(
        2,
        db.queryForObject(
            "select attempts from device_command where booking_id=?", Integer.class, id));
  }

  @Test
  void cancellationDeadline() {
    String id = id(reserve(user, 1, "cancel001"));
    service.action(user, id, "pay");
    code("CANCEL_WINDOW", () -> service.action(user, id, "cancel"));
    service.action(manager, id, "cancel");
  }

  @Test
  void staffRevocationAndStoreScope() {
    Map<String, Object> r =
        (Map<String, Object>)
            staff.save(
                owner,
                map(
                    "username",
                    "stafftest",
                    "password",
                    "Demo-password-12345",
                    "role",
                    "MANAGER",
                    "storeId",
                    2));
    Map<String, Object> s = (Map<String, Object>) staff.login("stafftest", "Demo-password-12345");
    String token = "Bearer " + s.get("token");
    DemoAuth.Actor a = auth.require(token);
    code("FORBIDDEN", () -> service.roomState(a, 1, "READY"));
    staff.save(
        owner,
        map(
            "id",
            r.get("id"),
            "username",
            "stafftest",
            "password",
            "",
            "role",
            "MANAGER",
            "storeId",
            2,
            "enabled",
            false));
    code("UNAUTHORIZED", () -> auth.require(token));
    code("LOGIN_FAILED", () -> staff.login("stafftest", "Demo-password-12345"));
  }

  @Test
  void storeRoomConfigurationAndScope() {
    Map<String, Object> s =
        (Map<String, Object>)
            ops.saveStore(
                owner,
                null,
                map(
                    "name",
                    "测试新店",
                    "address",
                    "测试地址",
                    "openMinute",
                    600,
                    "closeMinute",
                    1320,
                    "cleaningMinutes",
                    20,
                    "earlyMinutes",
                    0,
                    "cancelMinutes",
                    60));
    Map<String, Object> r =
        (Map<String, Object>)
            ops.saveRoom(
                owner,
                null,
                map("name", "测试房", "storeId", s.get("id"), "capacity", 4, "priceCents", 5000));
    assertNotNull(r.get("id"));
    code(
        "FORBIDDEN",
        () ->
            ops.saveRoom(
                manager,
                null,
                map("name", "非法房", "storeId", 1, "capacity", 4, "priceCents", 5000)));
  }

  @Test
  void scheduledFinishAndNoShow() {
    start = System.currentTimeMillis() - 1000;
    String id = id(reserve(user, 1, "autoflow1"));
    service.action(user, id, "pay");
    service.action(user, id, "open");
    db.update("update booking set ends_at=? where id=?", System.currentTimeMillis() - 1000, id);
    jobs.run();
    assertEquals("COMPLETED", service.booking(id).get("state"));
    assertEquals("CLEANING", service.rooms().get(0).get("state"));
    String otherId = id(reserve(other, 2, "noshow001"));
    service.action(other, otherId, "pay");
    db.update(
        "update booking set ends_at=? where id=?", System.currentTimeMillis() - 1000, otherId);
    jobs.run();
    assertEquals("NO_SHOW", service.booking(otherId).get("state"));
  }

  @Test
  void reminderDeduplication() {
    String id = id(reserve(user, 1, "reminder1"));
    service.action(user, id, "pay");
    db.update(
        "update booking set ends_at=? where id=?", System.currentTimeMillis() + 5 * 60000, id);
    jobs.run();
    jobs.run();
    assertEquals(1, ops.notifications(user).size());
    assertTrue(ops.notifications(other).isEmpty());
  }

  @Test
  void pendingExtensionExpires() {
    String id = id(reserve(user, 1, "extExpire"));
    service.action(user, id, "pay");
    Map<String, Object> e = (Map<String, Object>) service.extend(user, id, 60, "extExpiry");
    db.update(
        "update extension_order set created_at=? where id=?",
        System.currentTimeMillis() - 6 * 60000,
        e.get("id"));
    service.bookings(user);
    assertEquals(
        "EXPIRED",
        db.queryForObject(
            "select state from extension_order where id=?", String.class, e.get("id")));
    assertNotNull(service.reserve(other, 1, start + 4500000, start + 8100000, "extFreed1"));
  }

  @Test
  void httpAdminEndpointsRequireAuthentication() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/configuration"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    String token = (String) auth.login("guest").get("token");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/finance")
                .header("Authorization", "Bearer " + token))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/health"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }

  @Test
  void wechatIdentityNeverAcceptsClientRoleOrReturnsSessionKey() {
    WeChatAuthService w = new WeChatAuthService(db, auth, "test-app", "fake-secret");
    org.springframework.test.web.client.MockRestServiceServer server =
        org.springframework.test.web.client.MockRestServiceServer.createServer(w.http);
    server
        .expect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                "https://api.weixin.qq.com/sns/jscode2session?appid=test-app&secret=fake-secret&js_code=test-code&grant_type=authorization_code"))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                "{\"openid\":\"test-openid\",\"session_key\":\"must-not-return\"}",
                org.springframework.http.MediaType.APPLICATION_JSON));
    Map<String, Object> s = (Map<String, Object>) w.login("test-code");
    assertFalse(s.containsKey("session_key"));
    assertEquals("USER", ((DemoAuth.Actor) s.get("actor")).role);
    server.verify();
    db.update("delete from customer where openid='test-openid'");
  }

  @Test
  void deviceCloseFailureAutoRetryAndCleaning() {
    start = System.currentTimeMillis() - 1000;
    String id = id(reserve(user, 1, "closefail"));
    service.action(user, id, "pay");
    service.action(user, id, "open");
    workflow.fault(owner, "device:1:CLOSE", 1);
    db.update("update booking set ends_at=? where id=?", System.currentTimeMillis() - 1000, id);
    jobs.run();
    assertEquals("IN_USE", service.booking(id).get("state"));
    assertEquals(1, ((List<?>) ops.overview(owner).get("alerts")).size());
    jobs.run();
    assertEquals("COMPLETED", service.booking(id).get("state"));
  }

  @Test
  void futureBookingWhileRoomInUseAllowed() {
    start = System.currentTimeMillis() - 1000;
    String id = id(reserve(user, 1, "future001"));
    service.action(user, id, "pay");
    service.action(user, id, "open");
    assertNotNull(service.reserve(other, 1, start + 4500000, start + 8100000, "future002"));
  }

  @Test
  void configurationDoesNotMoveActiveBookedRoom() {
    reserve(user, 1, "moveRoom1");
    code(
        "ACTIVE_BOOKINGS",
        () ->
            ops.saveRoom(
                owner, 1L, map("name", "迁店测试", "storeId", 2, "capacity", 4, "priceCents", 3800)));
  }

  @Test
  void httpExtensionReadAndWriteRoundTrip() throws Exception {
    Map<String, Object> session = auth.login("guest");
    String header = "Bearer " + session.get("token");
    DemoAuth.Actor a = auth.require(header);
    String bid = id(reserve(a, 1, "httpExt01"));
    service.action(a, bid, "pay");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/bookings/" + bid + "/extensions")
                .header("Authorization", header))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    String json =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/bookings/" + bid + "/extensions")
                    .header("Authorization", header)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"minutes\":60,\"requestKey\":\"http-extension-key\"}"))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String eid =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("id").asText();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/extensions/" + eid + "/pay")
                .header("Authorization", header))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    assertEquals(start + 7200000, service.number(service.booking(bid), "ends_at"));
  }

  @Test
  void h2BackupRestoresIntoSeparateDatabase() throws Exception {
    HealthController health = new HealthController(db, auth);
    String token = "Bearer " + auth.login("owner").get("token");
    reserve(user, 1, "backup001");
    Map<String, Object> r = (Map<String, Object>) health.backup(token);
    java.nio.file.Path file =
        java.nio.file.Paths.get(".local", "backups", (String) r.get("filename")).toAbsolutePath();
    try {
      org.springframework.jdbc.core.JdbcTemplate restored =
          new org.springframework.jdbc.core.JdbcTemplate(
              new org.springframework.jdbc.datasource.DriverManagerDataSource(
                  "jdbc:h2:mem:restore_"
                      + UUID.randomUUID().toString().replace("-", "")
                      + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                  "sa",
                  ""));
      restored.execute("RUNSCRIPT FROM '" + file.toString().replace("'", "''") + "'");
      assertEquals(1, restored.queryForObject("select count(*) from booking", Integer.class));
      assertEquals(3, restored.queryForObject("select count(*) from room", Integer.class));
      restored.execute("SHUTDOWN");
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  @Test
  void fractionalCentRoundsUpNotFree() {
    db.update("update room set price_cents=1 where id=1");
    try {
      Map<String, Object> b = service.reserve(user, 1, start, start + 1800000, "round0001");
      assertEquals(1L, service.number(b, "amount_cents"));
    } finally {
      db.update("update room set price_cents=3800 where id=1");
    }
  }

  @Test
  void openingHoursRejectOutsideDay() {
    db.update("update store set open_minute=600,close_minute=1320 where id=1");
    java.time.LocalDate tomorrow =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
    long outside =
        tomorrow
            .atTime(9, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    code("OUTSIDE_HOURS", () -> service.reserve(user, 1, outside, outside + 3600000, "hours0001"));
    long inside =
        tomorrow
            .atTime(10, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    assertNotNull(service.reserve(user, 1, inside, inside + 3600000, "hours0002"));
  }
}
