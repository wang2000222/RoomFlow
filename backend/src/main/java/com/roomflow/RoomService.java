package com.roomflow;

import static com.roomflow.DemoAuth.Actor;

import java.time.*;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {
  final JdbcTemplate db;
  final DemoAuth auth;
  final DeviceGateway devices;
  final WorkflowService workflow;

  public RoomService(
      JdbcTemplate db, DemoAuth auth, DeviceGateway devices, WorkflowService workflow) {
    this.db = db;
    this.auth = auth;
    this.devices = devices;
    this.workflow = workflow;
  }

  @PostConstruct
  public void seed() {
    if (auth.isDemo() && db.queryForObject("select count(*) from store", Integer.class) == 0) {
      db.update(
          "insert into store(id,name,address) values(1,?,?)", "青禾 · 城南店", "演示地址 · 城南商业街 18 号");
      db.update("insert into store(id,name,address) values(2,?,?)", "青禾 · 湖畔店", "演示地址 · 湖畔路 66 号");
      db.update(
          "insert into room(id,store_id,name,capacity,price_cents,state) values(1,1,?,4,3800,'READY')",
          "听雨 · 标准四人间");
      db.update(
          "insert into room(id,store_id,name,capacity,price_cents,state) values(2,1,?,6,5800,'READY')",
          "竹影 · 六人雅间");
      db.update(
          "insert into room(id,store_id,name,capacity,price_cents,state) values(3,2,?,4,4800,'READY')",
          "观湖 · 景观四人间");
    }
  }

  public List<Map<String, Object>> stores() {
    return db.queryForList("select * from store where enabled=true order by id");
  }

  public List<Map<String, Object>> rooms() {
    return db.queryForList(
        "select r.*,s.name as store_name,s.cleaning_minutes,s.early_minutes from room r join store s on s.id=r.store_id where r.enabled=true and s.enabled=true order by r.id");
  }

  Map<String, Object> room(long id) {
    List<Map<String, Object>> rows =
        db.queryForList("select * from room where id=? for update", id);
    if (rows.isEmpty()) throw new ApiProblem(404, "NOT_FOUND", "包间不存在");
    return rows.get(0);
  }

  Map<String, Object> booking(String id) {
    List<Map<String, Object>> rows =
        db.queryForList(
            "select b.*,r.store_id,r.name as room_name from booking b join room r on b.room_id=r.id where b.id=?",
            id);
    if (rows.isEmpty()) throw new ApiProblem(404, "NOT_FOUND", "预约不存在");
    return rows.get(0);
  }

  long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  void ownOrStaff(Actor actor, Map<String, Object> booking) {
    if (!actor.id.equals(booking.get("user_id"))) auth.staff(actor, number(booking, "store_id"));
  }

  void audit(Actor actor, String action, String target) {
    db.update(
        "insert into audit values(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor.id,
        action,
        target,
        System.currentTimeMillis());
  }

  @Transactional
  public Map<String, Object> reserve(Actor actor, long roomId, long start, long end, String key) {
    return reservePackage(actor, roomId, start, end, key, null);
  }

  @Transactional
  public Map<String, Object> reservePackage(
      Actor actor, long roomId, long start, long end, String key, String packageId) {
    if (!"USER".equals(actor.role)) throw new ApiProblem(403, "USER_REQUIRED", "请使用用户账号预约");
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}"))
      throw new ApiProblem(400, "KEY_INVALID", "预约请求标识无效");
    Map<String, Object> room = room(roomId);
    Map<String, Object> store =
        db.queryForMap("select * from store where id=?", room.get("store_id"));
    List<Map<String, Object>> existing =
        db.queryForList("select * from booking where user_id=? and request_key=?", actor.id, key);
    if (!existing.isEmpty()) {
      Map<String, Object> old = existing.get(0);
      if (number(old, "room_id") != roomId
          || number(old, "starts_at") != start
          || number(old, "ends_at") != end)
        throw new ApiProblem(409, "KEY_REUSED", "请为不同预约使用新请求标识");
      return booking((String) old.get("id"));
    }
    long now = System.currentTimeMillis(), duration = end - start;
    if (start < now - 60000
        || start > now + 30L * 86400000
        || duration < 1800000
        || duration > 12 * 3600000L
        || duration % 1800000 != 0)
      throw new ApiProblem(400, "TIME_INVALID", "请选择未来30天内、30分钟至12小时的预约，时长按半小时递增");
    if (!Boolean.TRUE.equals(room.get("enabled"))
        || !Boolean.TRUE.equals(store.get("enabled"))
        || "MAINTENANCE".equals(room.get("state")))
      throw new ApiProblem(409, "ROOM_UNAVAILABLE", "包间维护中或已下架");
    if (!"READY".equals(room.get("state"))
        && start - number(store, "early_minutes") * 60000
            < now + number(store, "cleaning_minutes") * 60000)
      throw new ApiProblem(409, "ROOM_UNAVAILABLE", "包间当前使用或保洁中，请预约更晚时段");
    validateHours(store, start, end);
    expirePending();
    int clean = ((Number) store.get("cleaning_minutes")).intValue(),
        early = ((Number) store.get("early_minutes")).intValue();
    conflict(roomId, start - early * 60000L, end + clean * 60000L, "");
    long amount = (number(room, "price_cents") * duration + 3599999) / 3600000;
    if (packageId != null && !packageId.isEmpty()) {
      List<Map<String, Object>> packs =
          db.queryForList(
              "select * from price_package where id=? and room_id=? and enabled=true",
              packageId,
              roomId);
      if (packs.isEmpty() || number(packs.get(0), "minutes") * 60000 != duration)
        throw new ApiProblem(400, "PACKAGE_INVALID", "套餐与包间或时长不匹配");
      amount = number(packs.get(0), "amount_cents");
    }
    String id = UUID.randomUUID().toString();
    db.update(
        "insert into booking(id,user_id,room_id,starts_at,ends_at,amount_cents,state,request_key,created_at,hourly_cents,cleaning_minutes,early_minutes,cancel_minutes) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        actor.id,
        roomId,
        start,
        end,
        amount,
        "PENDING",
        key,
        now,
        room.get("price_cents"),
        clean,
        early,
        store.get("cancel_minutes"));
    audit(actor, "RESERVE", id);
    return booking(id);
  }

  void expirePending() {
    db.update(
        "update booking set state='EXPIRED' where state='PENDING' and created_at<?",
        System.currentTimeMillis() - 15 * 60000);
    db.update(
        "update extension_order set state='EXPIRED' where state='PENDING' and created_at<?",
        System.currentTimeMillis() - 5 * 60000);
  }

  void validateHours(Map<String, Object> store, long start, long end) {
    ZonedDateTime from = Instant.ofEpochMilli(start).atZone(ZoneId.of("Asia/Shanghai")),
        to = Instant.ofEpochMilli(end).atZone(ZoneId.of("Asia/Shanghai"));
    int open = ((Number) store.get("open_minute")).intValue(),
        close = ((Number) store.get("close_minute")).intValue();
    if (open == 0 && close == 1440) return;
    int fromMinute = from.getHour() * 60 + from.getMinute();
    double toMinute = to.getHour() * 60 + to.getMinute() + to.getSecond() / 60.0;
    boolean midnight =
        to.toLocalTime().equals(LocalTime.MIDNIGHT)
            && to.toLocalDate().equals(from.toLocalDate().plusDays(1));
    if (midnight) toMinute = 1440;
    if (fromMinute < open
        || toMinute > close
        || (!midnight && !from.toLocalDate().equals(to.toLocalDate())))
      throw new ApiProblem(409, "OUTSIDE_HOURS", "预约超出门店营业时间");
  }

  void conflict(long roomId, long start, long end, String excluded) {
    int count =
        db.queryForObject(
            "select count(*) from booking b where b.room_id=? and b.id<>? and b.state in ('PENDING','PAID','IN_USE') and b.starts_at-b.early_minutes*60000<? and coalesce((select max(e.new_end) from extension_order e where e.booking_id=b.id and e.state='PENDING'),b.ends_at)+b.cleaning_minutes*60000>?",
            Integer.class,
            roomId,
            excluded,
            end,
            start);
    if (count > 0) throw new ApiProblem(409, "SLOT_CONFLICT", "时段含提前入场和保洁缓冲已被占用");
  }

  @Transactional
  public List<Map<String, Object>> bookings(Actor actor) {
    expirePending();
    String base =
        "select b.*,r.store_id,r.name as room_name,s.name as store_name from booking b join room r on r.id=b.room_id join store s on s.id=r.store_id";
    if ("OWNER".equals(actor.role)) return db.queryForList(base + " order by b.created_at desc");
    if ("MANAGER".equals(actor.role))
      return db.queryForList(
          base + " where r.store_id=? order by b.created_at desc", actor.storeId);
    return db.queryForList(base + " where b.user_id=? order by b.created_at desc", actor.id);
  }

  @Transactional
  public Map<String, Object> action(Actor actor, String id, String action) {
    Map<String, Object> before = booking(id);
    room(number(before, "room_id"));
    Map<String, Object> b = booking(id);
    ownOrStaff(actor, b);
    expirePending();
    b = booking(id);
    String state = (String) b.get("state");
    long now = System.currentTimeMillis();
    if ("pay".equals(action)) {
      auth.requireDemo();
      if (!actor.id.equals(b.get("user_id"))) throw new ApiProblem(403, "FORBIDDEN", "只能支付自己的预约");
      if ("PAID".equals(state)) return b;
      require(state, "PENDING");
      if (number(b, "starts_at") < now - 60000)
        throw new ApiProblem(409, "START_PASSED", "预约开始时间已过，请取消后重新预约");
      db.update("update booking set state='PAID' where id=?", id);
      workflow.payment(id, null, number(b, "amount_cents"), "BOOKING");
    } else if ("cancel".equals(action)) {
      if ("CANCELLED".equals(state)) return b;
      if (!"PENDING".equals(state) && !"PAID".equals(state))
        throw new ApiProblem(409, "STATE_INVALID", "当前预约不能取消");
      if ("PAID".equals(state)
          && "USER".equals(actor.role)
          && now > number(b, "starts_at") - number(b, "cancel_minutes") * 60000)
        throw new ApiProblem(409, "CANCEL_WINDOW", "已超过自助取消时间，请联系门店处理");
      if ("PAID".equals(state)) {
        auth.requireDemo();
        workflow.refund(b);
      }
      db.update(
          "update extension_order set state='CANCELLED' where booking_id=? and state='PENDING'",
          id);
      db.update("update booking set state='CANCELLED' where id=?", id);
    } else if ("open".equals(action)) {
      auth.requireDemo();
      if ("IN_USE".equals(state)) return b;
      require(state, "PAID");
      if (now < number(b, "starts_at") - number(b, "early_minutes") * 60000
          || now >= number(b, "ends_at"))
        throw new ApiProblem(409, "OUTSIDE_WINDOW", "尚未到允许入场时间或预约已结束");
      Map<String, Object> r = room(number(b, "room_id"));
      require((String) r.get("state"), "READY");
      int active =
          db.queryForObject(
              "select count(*) from booking where room_id=? and state='IN_USE'",
              Integer.class,
              number(b, "room_id"));
      if (active > 0) throw new ApiProblem(409, "ROOM_OCCUPIED", "包间仍在使用");
      if (!workflow.command(b, "OPEN")) return booking(id);
      db.update("update booking set state='IN_USE' where id=?", id);
      db.update("update room set state='IN_USE' where id=?", number(b, "room_id"));
    } else if ("finish".equals(action)) {
      auth.requireDemo();
      if ("COMPLETED".equals(state)) return b;
      require(state, "IN_USE");
      if (!workflow.command(b, "CLOSE")) return booking(id);
      db.update("update booking set state='COMPLETED' where id=?", id);
      db.update("update room set state='CLEANING' where id=?", number(b, "room_id"));
    } else if ("no-show".equals(action)) {
      auth.staff(actor, number(b, "store_id"));
      require(state, "PAID");
      if (now < number(b, "ends_at")) throw new ApiProblem(409, "TIME_INVALID", "预约尚未结束");
      db.update("update booking set state='NO_SHOW' where id=?", id);
    } else throw new ApiProblem(400, "ACTION_INVALID", "操作不存在");
    audit(actor, "SIMULATED_" + action.toUpperCase(Locale.ROOT), id);
    return booking(id);
  }

  @Transactional
  public Object extend(Actor actor, String id, int minutes, String key) {
    Map<String, Object> before = booking(id);
    room(number(before, "room_id"));
    Map<String, Object> b = booking(id);
    if (!actor.id.equals(b.get("user_id"))) throw new ApiProblem(403, "FORBIDDEN", "仅用户本人可续时");
    if (!Arrays.asList("PAID", "IN_USE").contains(b.get("state"))
        || number(b, "ends_at") <= System.currentTimeMillis())
      throw new ApiProblem(409, "STATE_INVALID", "订单已结束或不可续时");
    if (minutes < 30
        || minutes > 720
        || minutes % 30 != 0
        || key == null
        || !key.matches("[A-Za-z0-9_-]{8,80}"))
      throw new ApiProblem(400, "INPUT_INVALID", "续时按半小时递增且需要请求标识");
    expirePending();
    List<Map<String, Object>> existing =
        db.queryForList("select * from extension_order where request_key=?", key);
    if (!existing.isEmpty()) {
      Map<String, Object> old = existing.get(0);
      if (!id.equals(old.get("booking_id"))
          || number(old, "new_end") - number(old, "old_end") != minutes * 60000L)
        throw new ApiProblem(409, "KEY_REUSED", "续时请求标识已使用");
      return old;
    }
    if (db.queryForObject(
            "select count(*) from extension_order where booking_id=? and state='PENDING'",
            Integer.class,
            id)
        > 0) throw new ApiProblem(409, "EXTENSION_PENDING", "请先完成或等待当前续时订单超时");
    long end = number(b, "ends_at") + minutes * 60000L;
    if (end - number(b, "starts_at") > 12 * 3600000L)
      throw new ApiProblem(400, "TIME_INVALID", "单次使用累计不超过12小时");
    validateHours(
        db.queryForMap("select * from store where id=?", b.get("store_id")),
        number(b, "starts_at"),
        end);
    conflict(
        number(b, "room_id"),
        number(b, "starts_at") - number(b, "early_minutes") * 60000,
        end + number(b, "cleaning_minutes") * 60000,
        id);
    String eid = UUID.randomUUID().toString();
    db.update(
        "insert into extension_order values(?,?,?,?,?,?,?,?)",
        eid,
        id,
        number(b, "ends_at"),
        end,
        (number(b, "hourly_cents") * minutes + 59) / 60,
        "PENDING",
        key,
        System.currentTimeMillis());
    audit(actor, "EXTEND_PENDING", id);
    return db.queryForMap("select * from extension_order where id=?", eid);
  }

  @Transactional
  public Object payExtension(Actor actor, String eid) {
    auth.requireDemo();
    Map<String, Object> e = db.queryForMap("select * from extension_order where id=?", eid);
    Map<String, Object> before = booking((String) e.get("booking_id"));
    room(number(before, "room_id"));
    Map<String, Object> b = booking((String) e.get("booking_id"));
    if (!actor.id.equals(b.get("user_id"))) throw new ApiProblem(403, "FORBIDDEN", "仅可支付自己的续时");
    e = db.queryForMap("select * from extension_order where id=?", eid);
    if ("PAID".equals(e.get("state"))) return e;
    require((String) e.get("state"), "PENDING");
    if (number(e, "created_at") < System.currentTimeMillis() - 5 * 60000
        || number(b, "ends_at") <= System.currentTimeMillis()
        || !Arrays.asList("PAID", "IN_USE").contains(b.get("state"))
        || number(e, "old_end") != number(b, "ends_at"))
      throw new ApiProblem(409, "EXTENSION_EXPIRED", "续时订单超时或原预约已变更");
    conflict(
        number(b, "room_id"),
        number(b, "starts_at") - number(b, "early_minutes") * 60000,
        number(e, "new_end") + number(b, "cleaning_minutes") * 60000,
        (String) b.get("id"));
    db.update(
        "update booking set ends_at=?,amount_cents=amount_cents+? where id=?",
        e.get("new_end"),
        e.get("amount_cents"),
        b.get("id"));
    db.update("update extension_order set state='PAID' where id=?", eid);
    workflow.payment((String) b.get("id"), eid, number(e, "amount_cents"), "EXTENSION");
    audit(actor, "EXTEND_PAID", (String) b.get("id"));
    return db.queryForMap("select * from extension_order where id=?", eid);
  }

  @Transactional
  public void remark(Actor actor, String id, String remark) {
    Map<String, Object> b = booking(id);
    auth.staff(actor, number(b, "store_id"));
    if (remark == null || remark.length() > 255)
      throw new ApiProblem(400, "INPUT_INVALID", "备注最多255字");
    db.update("update booking set remark=? where id=?", remark, id);
    audit(actor, "ORDER_REMARK", id);
  }

  void require(String actual, String expected) {
    if (!expected.equals(actual)) throw new ApiProblem(409, "STATE_INVALID", "当前状态不允许此操作");
  }

  public List<Map<String, Object>> extensions(Actor a, String id) {
    ownOrStaff(a, booking(id));
    return db.queryForList(
        "select * from extension_order where booking_id=? order by created_at desc", id);
  }

  @Transactional
  public void roomState(Actor actor, long id, String state) {
    Map<String, Object> room = room(id);
    auth.staff(actor, number(room, "store_id"));
    if (!Arrays.asList("READY", "MAINTENANCE").contains(state))
      throw new ApiProblem(400, "STATE_INVALID", "可设置为可预约或维护中");
    if ("IN_USE".equals(room.get("state")))
      throw new ApiProblem(409, "ROOM_OCCUPIED", "使用中的包间不能更改状态");
    db.update("update room set state=? where id=?", state, id);
    audit(actor, "ROOM_" + state, String.valueOf(id));
  }

  public List<Map<String, Object>> audits(Actor actor) {
    if (!"OWNER".equals(actor.role)) throw new ApiProblem(403, "FORBIDDEN", "仅商家负责人可查看全量操作日志");
    return db.queryForList("select * from audit order by created_at desc limit 100");
  }
}
