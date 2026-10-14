package com.roomflow;

import static com.roomflow.DemoAuth.Actor;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {
  final JdbcTemplate db;
  final DemoAuth auth;

  public OperationsService(JdbcTemplate db, DemoAuth auth) {
    this.db = db;
    this.auth = auth;
  }

  void owner(Actor a) {
    if (!"OWNER".equals(a.role)) throw new ApiProblem(403, "FORBIDDEN", "仅商家负责人可配置经营信息");
  }

  static String text(Map<String, Object> b, String key, int max) {
    String value = String.valueOf(b.getOrDefault(key, "")).trim();
    if (value.isEmpty() || value.length() > max)
      throw new ApiProblem(400, "INPUT_INVALID", key + " 不能为空或超长");
    return value;
  }

  static int integer(Map<String, Object> b, String key, int min, int max) {
    try {
      int n = Integer.parseInt(String.valueOf(b.get(key)));
      if (n < min || n > max) throw new IllegalArgumentException();
      return n;
    } catch (Exception e) {
      throw new ApiProblem(400, "INPUT_INVALID", key + " 超出范围");
    }
  }

  long allocate(String kind) {
    long id =
        db.queryForObject(
            "select next_id from id_allocator where kind=? for update", Long.class, kind);
    db.update("update id_allocator set next_id=? where kind=?", id + 1, kind);
    return id;
  }

  @Transactional
  public Object saveStore(Actor a, Long id, Map<String, Object> b) {
    owner(a);
    String name = text(b, "name", 100), address = text(b, "address", 255);
    int open = integer(b, "openMinute", 0, 1439), close = integer(b, "closeMinute", 1, 1440);
    if (close <= open) throw new ApiProblem(400, "HOURS_INVALID", "营业结束必须晚于开始；跨夜经营请选择全天营业");
    int clean = integer(b, "cleaningMinutes", 0, 120),
        early = integer(b, "earlyMinutes", 0, 30),
        cancel = integer(b, "cancelMinutes", 0, 1440);
    boolean enabled = !Boolean.FALSE.equals(b.get("enabled"));
    if (id == null) {
      id = allocate("store");
      db.update(
          "insert into store(id,name,address,open_minute,close_minute,cleaning_minutes,early_minutes,cancel_minutes,enabled) values(?,?,?,?,?,?,?,?,?)",
          id,
          name,
          address,
          open,
          close,
          clean,
          early,
          cancel,
          enabled);
    } else if (db.update(
            "update store set name=?,address=?,open_minute=?,close_minute=?,cleaning_minutes=?,early_minutes=?,cancel_minutes=?,enabled=? where id=?",
            name,
            address,
            open,
            close,
            clean,
            early,
            cancel,
            enabled,
            id)
        == 0) throw new ApiProblem(404, "NOT_FOUND", "门店不存在");
    audit(a, "STORE_CONFIG", String.valueOf(id));
    return db.queryForMap("select * from store where id=?", id);
  }

  @Transactional
  public Object saveRoom(Actor a, Long id, Map<String, Object> b) {
    owner(a);
    long store = integer(b, "storeId", 1, Integer.MAX_VALUE);
    if (db.queryForObject("select count(*) from store where id=?", Integer.class, store) == 0)
      throw new ApiProblem(404, "NOT_FOUND", "门店不存在");
    String name = text(b, "name", 80);
    int capacity = integer(b, "capacity", 1, 30), price = integer(b, "priceCents", 1, 1000000);
    boolean enabled = !Boolean.FALSE.equals(b.get("enabled"));
    if (id == null) {
      id = allocate("room");
      db.update(
          "insert into room(id,store_id,name,capacity,price_cents,state,enabled) values(?,?,?,?,?,'READY',?)",
          id,
          store,
          name,
          capacity,
          price,
          enabled);
    } else {
      Map<String, Object> r = db.queryForMap("select * from room where id=? for update", id);
      int active =
          db.queryForObject(
              "select count(*) from booking where room_id=? and state in ('PENDING','PAID','IN_USE')",
              Integer.class,
              id);
      if (active > 0 && (store != ((Number) r.get("store_id")).longValue() || !enabled))
        throw new ApiProblem(409, "ACTIVE_BOOKINGS", "有未完成预约，不能迁店或下架");
      db.update(
          "update room set store_id=?,name=?,capacity=?,price_cents=?,enabled=? where id=?",
          store,
          name,
          capacity,
          price,
          enabled,
          id);
    }
    audit(a, "ROOM_CONFIG", String.valueOf(id));
    return db.queryForMap("select * from room where id=?", id);
  }

  public List<Map<String, Object>> packages(long room) {
    return db.queryForList(
        "select * from price_package where room_id=? and enabled=true order by minutes", room);
  }

  @Transactional
  public Object savePackage(Actor a, Map<String, Object> b) {
    owner(a);
    long room = integer(b, "roomId", 1, Integer.MAX_VALUE);
    String name = text(b, "name", 80);
    int minutes = integer(b, "minutes", 30, 720);
    if (minutes % 30 != 0) throw new ApiProblem(400, "INPUT_INVALID", "套餐时长按半小时递增");
    int amount = integer(b, "amountCents", 1, 10000000);
    String id = b.get("id") == null ? UUID.randomUUID().toString() : text(b, "id", 36);
    if (db.queryForObject("select count(*) from room where id=?", Integer.class, room) == 0)
      throw new ApiProblem(404, "NOT_FOUND", "包间不存在");
    if (db.queryForObject("select count(*) from price_package where id=?", Integer.class, id) == 0)
      db.update(
          "insert into price_package values(?,?,?,?,?,?)",
          id,
          room,
          name,
          minutes,
          amount,
          !Boolean.FALSE.equals(b.get("enabled")));
    else
      db.update(
          "update price_package set name=?,minutes=?,amount_cents=?,enabled=? where id=? and room_id=?",
          name,
          minutes,
          amount,
          !Boolean.FALSE.equals(b.get("enabled")),
          id,
          room);
    audit(a, "PACKAGE_CONFIG", id);
    return db.queryForMap("select * from price_package where id=?", id);
  }

  void audit(Actor a, String action, String target) {
    db.update(
        "insert into audit values(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        a.id,
        action,
        target,
        System.currentTimeMillis());
  }

  public Map<String, Object> overview(Actor a) {
    if ("USER".equals(a.role)) throw new ApiProblem(403, "FORBIDDEN", "无权查看经营账务");
    String scope = "MANAGER".equals(a.role) ? " where r.store_id=" + a.storeId : "";
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "payments",
        db.queryForList(
            "select p.*,r.store_id from payment_record p join booking b on b.id=p.booking_id join room r on r.id=b.room_id"
                + scope
                + " order by p.created_at desc"));
    out.put(
        "refunds",
        db.queryForList(
            "select f.*,r.store_id from refund_record f join booking b on b.id=f.booking_id join room r on r.id=b.room_id"
                + scope
                + " order by f.created_at desc"));
    out.put(
        "commands",
        db.queryForList(
            "select d.*,r.store_id from device_command d join room r on r.id=d.room_id"
                + scope
                + " order by d.created_at desc"));
    out.put(
        "alerts",
        db.queryForList(
            "select d.id,d.booking_id,d.room_id,d.error from device_command d join room r on r.id=d.room_id"
                + (scope.isEmpty() ? " where " : scope + " and ")
                + "d.state='FAILED'"));
    return out;
  }

  public List<Map<String, Object>> notifications(Actor a) {
    String clause =
        "USER".equals(a.role) ? "b.user_id=?" : "MANAGER".equals(a.role) ? "r.store_id=?" : "1=1";
    Object[] args =
        "USER".equals(a.role)
            ? new Object[] {a.id}
            : "MANAGER".equals(a.role) ? new Object[] {a.storeId} : new Object[] {};
    return db.queryForList(
        "select n.* from notification n join booking b on b.id=n.booking_id join room r on r.id=b.room_id where "
            + clause
            + " order by n.created_at desc limit 100",
        args);
  }
}
