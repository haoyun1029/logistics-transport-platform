package cn.logistics.platform.service;

import cn.logistics.platform.repository.AccountRepository;
import cn.logistics.platform.security.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.logistics.platform.service.TransportBusinessService.*;

@Service
public class UserManagementService {
    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final TransportBusinessService business;
    public UserManagementService(JdbcTemplate jdbc,AccountRepository accounts,TransportBusinessService business){this.jdbc=jdbc;this.accounts=accounts;this.business=business;}
    public List<UserView> users(UserPrincipal actor) {
        requireStaff(actor);
        String filter=" WHERE u.account_status<>'DELETED' AND (u.role<>'DRIVER' OR u.account_status IN ('APPROVED','DISABLED'))";
        if(actor.role()==UserRole.DISPATCHER)filter+=" AND u.role='DRIVER'";
        return jdbc.query("SELECT u.id,u.username,u.display_name,u.phone,u.role,u.account_status,EXISTS(SELECT 1 FROM transport_tasks t WHERE t.driver_id=u.id AND t.task_status IN ('PENDING_ACCEPTANCE','IN_TRANSIT')) AS busy FROM users u"+filter+" ORDER BY u.id",
                (rs,n)->new UserView(rs.getLong("id"),rs.getString("username"),rs.getString("display_name"),rs.getString("phone"),rs.getString("role"),rs.getString("account_status"),rs.getBoolean("busy")));
    }
    @Transactional public long createStaff(StaffInput request,UserPrincipal actor) {
        requireSuper(actor);
        if(request.role()!=UserRole.ADMIN && request.role()!=UserRole.DISPATCHER)throw new IllegalArgumentException("只能创建普通管理员或调度员；司机需自行注册并审核。");
        String username=required(request.username(),32,"用户名").toLowerCase(Locale.ROOT);
        if(!username.matches("[a-z0-9_.-]{3,32}"))throw new IllegalArgumentException("用户名须为 3–32 位字母、数字、点、下划线或横线。");
        if(request.password()==null || request.password().length()<10 || request.password().length()>128)throw new IllegalArgumentException("密码须为 10–128 位。");
        if(accounts.findByUsername(username).isPresent())throw new IllegalArgumentException("用户名已存在。");
        long id=accounts.insert(username,PasswordHasher.hash(request.password()),request.role(),"APPROVED",required(request.displayName(),80,"姓名"),optional(request.phone(),32,"联系方式"));
        business.audit(actor.id(),"STAFF_CREATED",Long.toString(id),Map.of("role",request.role().name()));return id;
    }
    @Transactional public void contact(long id,ContactInput request,UserPrincipal actor) {
        requireStaff(actor);var target=lock(id);
        if("DELETED".equals(target.get("account_status")))throw new IllegalStateException("已删除账号不可编辑。");
        if("DRIVER".equals(target.get("role")) && !List.of("APPROVED","DISABLED").contains(target.get("account_status")))throw new IllegalStateException("未通过审核的司机申请不可编辑，请在司机审核页查看。");
        if(actor.role()!=UserRole.SUPER_ADMIN && !"DRIVER".equals(target.get("role")))throw new ForbiddenException("只能编辑司机资料。");
        jdbc.update("UPDATE users SET display_name=?,phone=? WHERE id=?",required(request.displayName(),80,"姓名"),optional(request.phone(),32,"联系方式"),id);
        business.audit(actor.id(),"USER_CONTACT_UPDATED",Long.toString(id),Map.of());
    }
    @Transactional public void role(long id,RoleInput request,UserPrincipal actor) {
        requireSuper(actor);var target=lock(id);guardMutable(id,target,actor);
        if(!List.of("ADMIN","DISPATCHER").contains(target.get("role")) || (request.role()!=UserRole.ADMIN && request.role()!=UserRole.DISPATCHER))throw new IllegalArgumentException("职员角色只允许普通管理员与调度员互换；司机身份通过注册审核维护。");
        if(!"APPROVED".equals(target.get("account_status")))throw new IllegalStateException("只能调整正常职员的角色。");
        jdbc.update("UPDATE users SET role=? WHERE id=?",request.role().name(),id);revoke(id);
        business.audit(actor.id(),"USER_ROLE_CHANGED",Long.toString(id),Map.of("role",request.role().name()));
    }
    @Transactional public void status(long id,StatusInput request,UserPrincipal actor) {
        requireSuper(actor);var target=lock(id);guardMutable(id,target,actor);
        if(request.enabled()==null)throw new IllegalArgumentException("请选择启用或停用。");
        String old=target.get("account_status").toString(),expected=request.enabled()?"DISABLED":"APPROVED";
        if(!expected.equals(old))throw new IllegalStateException("仅可停用正常账号或恢复已停用账号；申请审核请使用司机审核页。");
        jdbc.update("UPDATE users SET account_status=? WHERE id=?",request.enabled()?"APPROVED":"DISABLED",id);revoke(id);
        business.audit(actor.id(),"USER_STATUS_CHANGED",Long.toString(id),Map.of("enabled",request.enabled()));
    }
    private Map<String,Object> lock(long id){var rows=jdbc.queryForList("SELECT role,account_status FROM users WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new IllegalArgumentException("用户不存在。");return rows.getFirst();}
    private void guardMutable(long id,Map<String,Object> target,UserPrincipal actor){
        if(id==actor.id() || "SUPER_ADMIN".equals(target.get("role")))throw new IllegalArgumentException("不能调整本人或超级管理员的角色与状态。");
        if(!jdbc.queryForList("SELECT id FROM transport_tasks WHERE driver_id=? AND task_status IN ('PENDING_ACCEPTANCE','IN_TRANSIT') FOR UPDATE",Long.class,id).isEmpty())throw new IllegalStateException("司机已有任务，不能停用或调整角色。");
    }
    private void revoke(long id){jdbc.update("DELETE FROM api_sessions WHERE user_id=?",id);}
    private static void requireStaff(UserPrincipal actor){if(actor.role()==UserRole.DRIVER)throw new ForbiddenException("无权读取人员目录。");}
    private static void requireSuper(UserPrincipal actor){if(actor.role()!=UserRole.SUPER_ADMIN)throw new ForbiddenException("只有超级管理员可以管理职员与账号状态。");}
    public record UserView(long id,String username,String displayName,String phone,String role,String status,boolean busy){}
    public record StaffInput(String username,String password,String displayName,String phone,UserRole role){}
    public record ContactInput(String displayName,String phone){}
    public record RoleInput(UserRole role){}
    public record StatusInput(Boolean enabled){}
}
