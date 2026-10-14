package com.roomflow;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class DemoAuth {
  public static class Actor {
    public final String id, role;
    public final Long storeId;
    Actor(String id,String role,Long storeId) { this.id=id; this.role=role; this.storeId=storeId; }
  }
  private static class Session {
    final Actor actor; final long expires;
    Session(Actor actor) { this.actor=actor; this.expires=System.currentTimeMillis()+7200000; }
  }
  private final Map<String,Session> sessions=new ConcurrentHashMap<>();
  private final boolean demo;
  public DemoAuth(@Value("${roomflow.demo}") boolean demo) { this.demo=demo; }
  public Map<String,Object> login(String account) {
    if (!demo) throw new ApiProblem(503,"PROVIDER_REQUIRED","正式认证尚未接入，请勿以生产模式发布");
    Actor actor;
    switch(account==null?"":account) {
      case "owner": actor=new Actor("owner","OWNER",null); break;
      case "manager": actor=new Actor("manager","MANAGER",1L); break;
      case "guest": actor=new Actor("guest","USER",null); break;
      case "guest2": actor=new Actor("guest2","USER",null); break;
      default: throw new ApiProblem(400,"ACCOUNT_INVALID","请选择演示账号");
    }
    sessions.entrySet().removeIf(e -> e.getValue().expires < System.currentTimeMillis());
    String token=UUID.randomUUID().toString()+UUID.randomUUID().toString();
    sessions.put(token,new Session(actor));
    Map<String,Object> result=new LinkedHashMap<>(); result.put("token",token); result.put("actor",actor); result.put("demo",true); return result;
  }
  public Actor require(String header) {
    String token=header!=null&&header.startsWith("Bearer ")?header.substring(7):"";
    Session session=sessions.get(token);
    if(session==null||session.expires<System.currentTimeMillis()) throw new ApiProblem(401,"UNAUTHORIZED","请重新登录");
    return session.actor;
  }
  public void requireDemo() { if(!demo) throw new ApiProblem(503,"PROVIDER_REQUIRED","真实支付与设备尚未接入"); }
  public void staff(Actor actor,long storeId) {
    if(!"OWNER".equals(actor.role)&&!("MANAGER".equals(actor.role)&&Objects.equals(actor.storeId,storeId))) throw new ApiProblem(403,"FORBIDDEN","无权操作此门店");
  }
}
