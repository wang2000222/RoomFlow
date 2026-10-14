package com.roomflow;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.roomflow.DemoAuth.Actor;
@Service
public class RoomService {
  final JdbcTemplate db; final DemoAuth auth; final DeviceGateway devices;
  public RoomService(JdbcTemplate db,DemoAuth auth,DeviceGateway devices) { this.db=db; this.auth=auth; this.devices=devices; }
  @PostConstruct public void seed() {
    if(db.queryForObject("select count(*) from store",Integer.class)==0) {
      db.update("insert into store values(1,?,?)","青禾 · 城南店","演示地址 · 城南商业街 18 号");
      db.update("insert into store values(2,?,?)","青禾 · 湖畔店","演示地址 · 湖畔路 66 号");
      db.update("insert into room values(1,1,?,4,3800,'READY')","听雨 · 标准四人间");
      db.update("insert into room values(2,1,?,6,5800,'READY')","竹影 · 六人雅间");
      db.update("insert into room values(3,2,?,4,4800,'READY')","观湖 · 景观四人间");
    }
  }
  public List<Map<String,Object>> stores() { return db.queryForList("select * from store order by id"); }
  public List<Map<String,Object>> rooms() { return db.queryForList("select r.*,s.name as store_name from room r join store s on s.id=r.store_id order by r.id"); }
  Map<String,Object> room(long id) {
    List<Map<String,Object>> rows=db.queryForList("select * from room where id=? for update",id);
    if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND","包间不存在"); return rows.get(0);
  }
  Map<String,Object> booking(String id) {
    List<Map<String,Object>> rows=db.queryForList("select b.*,r.store_id,r.name as room_name from booking b join room r on b.room_id=r.id where b.id=?",id);
    if(rows.isEmpty()) throw new ApiProblem(404,"NOT_FOUND","预约不存在"); return rows.get(0);
  }
  long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
  void ownOrStaff(Actor actor,Map<String,Object> booking) { if(!actor.id.equals(booking.get("user_id"))) auth.staff(actor,number(booking,"store_id")); }
  void audit(Actor actor,String action,String target) { db.update("insert into audit values(?,?,?,?,?)",UUID.randomUUID().toString(),actor.id,action,target,System.currentTimeMillis()); }
  @Transactional public Map<String,Object> reserve(Actor actor,long roomId,long start,long end,String key) {
    if(!"USER".equals(actor.role)) throw new ApiProblem(403,"USER_REQUIRED","请使用用户账号预约");
    if(key==null||!key.matches("[A-Za-z0-9_-]{8,80}")) throw new ApiProblem(400,"KEY_INVALID","预约请求标识无效");
    Map<String,Object> room=room(roomId);
    List<Map<String,Object>> existing=db.queryForList("select * from booking where user_id=? and request_key=?",actor.id,key);
    if(!existing.isEmpty()) {
      Map<String,Object> old=existing.get(0);
      if(number(old,"room_id")!=roomId||number(old,"starts_at")!=start||number(old,"ends_at")!=end) throw new ApiProblem(409,"KEY_REUSED","请为不同预约使用新请求标识");
      return booking((String)old.get("id"));
    }
    long now=System.currentTimeMillis(),duration=end-start;
    if(start<now-60000||start>now+30L*86400000||duration<1800000||duration>12*3600000L||duration%1800000!=0) throw new ApiProblem(400,"TIME_INVALID","请选择未来30天内、30分钟至12小时的预约，时长按半小时递增");
    if(!"READY".equals(room.get("state"))) throw new ApiProblem(409,"ROOM_UNAVAILABLE","包间待保洁、维护中或使用中");
    expirePending();
    int overlaps=db.queryForObject("select count(*) from booking where room_id=? and state in ('PENDING','PAID','IN_USE') and starts_at<? and ends_at>?",Integer.class,roomId,end,start);
    if(overlaps>0) throw new ApiProblem(409,"SLOT_CONFLICT","该时段已被预约，请调整时间");
    long amount=number(room,"price_cents")*duration/3600000;
    String id=UUID.randomUUID().toString();
    db.update("insert into booking values(?,?,?,?,?,?,?,?,?)",id,actor.id,roomId,start,end,amount,"PENDING",key,now);
    audit(actor,"RESERVE",id); return booking(id);
  }
  void expirePending() { db.update("update booking set state='EXPIRED' where state='PENDING' and created_at<?",System.currentTimeMillis()-15*60000); }
  @Transactional public List<Map<String,Object>> bookings(Actor actor) {
    expirePending();
    String base="select b.*,r.store_id,r.name as room_name,s.name as store_name from booking b join room r on r.id=b.room_id join store s on s.id=r.store_id";
    if("OWNER".equals(actor.role)) return db.queryForList(base+" order by b.created_at desc");
    if("MANAGER".equals(actor.role)) return db.queryForList(base+" where r.store_id=? order by b.created_at desc",actor.storeId);
    return db.queryForList(base+" where b.user_id=? order by b.created_at desc",actor.id);
  }
  @Transactional public Map<String,Object> action(Actor actor,String id,String action) {
    Map<String,Object> before=booking(id); room(number(before,"room_id"));
    Map<String,Object> b=booking(id); ownOrStaff(actor,b);
    expirePending(); b=booking(id); String state=(String)b.get("state"); long now=System.currentTimeMillis();
    if("pay".equals(action)) {
      auth.requireDemo();
      if(!actor.id.equals(b.get("user_id"))) throw new ApiProblem(403,"FORBIDDEN","只能支付自己的预约");
      if("PAID".equals(state)) return b;
      require(state,"PENDING");
      if(number(b,"starts_at")<now-60000) throw new ApiProblem(409,"START_PASSED","预约开始时间已过，请取消后重新预约");
      db.update("update booking set state='PAID' where id=?",id);
    } else if("cancel".equals(action)) {
      if("CANCELLED".equals(state)) return b;
      if(!"PENDING".equals(state)&&!"PAID".equals(state)) throw new ApiProblem(409,"STATE_INVALID","当前预约不能取消");
      if("PAID".equals(state)) auth.requireDemo();
      db.update("update booking set state='CANCELLED' where id=?",id);
    } else if("open".equals(action)) {
      auth.requireDemo(); if("IN_USE".equals(state)) return b; require(state,"PAID");
      if(now<number(b,"starts_at")-15*60000||now>=number(b,"ends_at")) throw new ApiProblem(409,"OUTSIDE_WINDOW","仅可在预约开始前15分钟至结束时间内开门");
      Map<String,Object> r=room(number(b,"room_id")); require((String)r.get("state"),"READY");
      int active=db.queryForObject("select count(*) from booking where room_id=? and state='IN_USE'",Integer.class,number(b,"room_id"));
      if(active>0) throw new ApiProblem(409,"ROOM_OCCUPIED","包间仍在使用");
      devices.open(number(b,"room_id"),id);
      db.update("update booking set state='IN_USE' where id=?",id);
      db.update("update room set state='IN_USE' where id=?",number(b,"room_id"));
    } else if("finish".equals(action)) {
      auth.requireDemo(); if("COMPLETED".equals(state)) return b; require(state,"IN_USE");
      devices.close(number(b,"room_id"),id);
      db.update("update booking set state='COMPLETED' where id=?",id);
      db.update("update room set state='CLEANING' where id=?",number(b,"room_id"));
    } else throw new ApiProblem(400,"ACTION_INVALID","操作不存在");
    audit(actor,"SIMULATED_"+action.toUpperCase(Locale.ROOT),id); return booking(id);
  }
  void require(String actual,String expected) { if(!expected.equals(actual)) throw new ApiProblem(409,"STATE_INVALID","当前状态不允许此操作"); }
  @Transactional public void roomState(Actor actor,long id,String state) {
    Map<String,Object> room=room(id); auth.staff(actor,number(room,"store_id"));
    if(!Arrays.asList("READY","MAINTENANCE").contains(state)) throw new ApiProblem(400,"STATE_INVALID","可设置为可预约或维护中");
    if("IN_USE".equals(room.get("state"))) throw new ApiProblem(409,"ROOM_OCCUPIED","使用中的包间不能更改状态");
    db.update("update room set state=? where id=?",state,id); audit(actor,"ROOM_"+state,String.valueOf(id));
  }
  public List<Map<String,Object>> audits(Actor actor) {
    if(!"OWNER".equals(actor.role)) throw new ApiProblem(403,"FORBIDDEN","仅商家负责人可查看全量操作日志");
    return db.queryForList("select * from audit order by created_at desc limit 100");
  }
}
