package com.roomflow;

import static com.roomflow.DemoAuth.Actor;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {
  final JdbcTemplate db;
  final DemoAuth auth;
  final DeviceGateway devices;

  public WorkflowService(JdbcTemplate db, DemoAuth auth, DeviceGateway devices) {
    this.db = db;
    this.auth = auth;
    this.devices = devices;
  }

  void payment(String booking, String extension, long amount, String kind) {
    db.update(
        "insert into payment_record(id,booking_id,extension_id,amount_cents,state,kind,created_at) values(?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        booking,
        extension,
        amount,
        "SUCCESS",
        kind,
        System.currentTimeMillis());
  }

  boolean fail(String key) {
    List<Map<String, Object>> r =
        db.queryForList("select * from simulation_fault where fault_key=? for update", key);
    if (r.isEmpty() || ((Number) r.get(0).get("remaining")).intValue() <= 0) return false;
    db.update("update simulation_fault set remaining=remaining-1 where fault_key=?", key);
    return true;
  }

  boolean command(Map<String, Object> b, String action) {
    auth.requireDemo();
    String bid = (String) b.get("id");
    long room = ((Number) b.get("room_id")).longValue();
    List<Map<String, Object>> old =
        db.queryForList(
            "select * from device_command where booking_id=? and action=?", bid, action);
    String id = old.isEmpty() ? UUID.randomUUID().toString() : (String) old.get(0).get("id");
    if (!old.isEmpty() && "SUCCESS".equals(old.get(0).get("state"))) return true;
    if (old.isEmpty())
      db.update(
          "insert into device_command(id,booking_id,room_id,action,state,created_at) values(?,?,?,?,?,?)",
          id,
          bid,
          room,
          action,
          "PENDING",
          System.currentTimeMillis());
    try {
      if (fail("device:" + room + ":" + action)) throw new IllegalStateException("模拟设备离线，未收到成功回执");
      if ("OPEN".equals(action)) devices.open(room, bid);
      else devices.close(room, bid);
      db.update(
          "update device_command set state='SUCCESS',attempts=attempts+1,error=null where id=?",
          id);
      return true;
    } catch (Exception e) {
      db.update(
          "update device_command set state='FAILED',attempts=attempts+1,error=? where id=?",
          "设备操作失败，需重试或现场处理",
          id);
      notify(bid, "DEVICE_" + action + "_FAILED", "设备操作未确认成功，请联系门店处理");
      return false;
    }
  }

  void refund(Map<String, Object> b) {
    String bid = (String) b.get("id");
    if (db.queryForObject(
            "select count(*) from refund_record where booking_id=?", Integer.class, bid)
        == 0)
      db.update(
          "insert into refund_record(id,booking_id,amount_cents,state,created_at) values(?,?,?,'PENDING',?)",
          UUID.randomUUID().toString(),
          bid,
          b.get("amount_cents"),
          System.currentTimeMillis());
    processRefund(bid);
  }

  void processRefund(String bid) {
    auth.requireDemo();
    Map<String, Object> r =
        db.queryForMap("select * from refund_record where booking_id=? for update", bid);
    if ("SUCCESS".equals(r.get("state"))) return;
    boolean failed = fail("refund:" + bid);
    db.update(
        "update refund_record set state=?,attempts=attempts+1,error=? where booking_id=?",
        failed ? "FAILED" : "SUCCESS",
        failed ? "模拟退款渠道暂不可用" : null,
        bid);
    if (failed) notify(bid, "REFUND_FAILED", "预约已取消，退款失败待处理；请查看退款记录");
  }

  void notify(String bid, String kind, String message) {
    if (db.queryForObject(
            "select count(*) from notification where booking_id=? and kind=?",
            Integer.class,
            bid,
            kind)
        == 0)
      db.update(
          "insert into notification values(?,?,?,?,?)",
          UUID.randomUUID().toString(),
          bid,
          kind,
          message,
          System.currentTimeMillis());
  }

  @Transactional
  public Object retryRefund(Actor a, String id) {
    Map<String, Object> r =
        db.queryForMap(
            "select f.*,r.store_id from refund_record f join booking b on b.id=f.booking_id join room r on r.id=b.room_id where f.id=?",
            id);
    auth.staff(a, ((Number) r.get("store_id")).longValue());
    processRefund((String) r.get("booking_id"));
    return db.queryForMap("select * from refund_record where id=?", id);
  }

  @Transactional
  public void fault(Actor a, String key, int count) {
    if (!"OWNER".equals(a.role)) throw new ApiProblem(403, "FORBIDDEN", "仅负责人可以测试故障");
    auth.requireDemo();
    if (key == null
        || !key.matches("(device:[0-9]+:(OPEN|CLOSE)|refund:[a-f0-9-]{36})")
        || count < 0
        || count > 5) throw new ApiProblem(400, "INPUT_INVALID", "故障标识或次数错误");
    if (db.queryForObject(
            "select count(*) from simulation_fault where fault_key=?", Integer.class, key)
        == 0) db.update("insert into simulation_fault values(?,?)", key, count);
    else db.update("update simulation_fault set remaining=? where fault_key=?", count, key);
  }

  @Transactional
  public void reconcile(Actor a) {
    if (!"OWNER".equals(a.role)) throw new ApiProblem(403, "FORBIDDEN", "仅负责人可对账");
    auth.requireDemo();
    for (Map<String, Object> r :
        db.queryForList(
            "select booking_id from refund_record where state in ('PENDING','FAILED') and attempts<3"))
      processRefund((String) r.get("booking_id"));
    db.update(
        "insert into audit values(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        a.id,
        "SIMULATED_RECONCILE",
        "refunds",
        System.currentTimeMillis());
  }
}
