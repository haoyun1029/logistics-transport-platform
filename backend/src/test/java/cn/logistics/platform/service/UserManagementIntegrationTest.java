package cn.logistics.platform.service;

import cn.logistics.platform.repository.AccountRepository;
import cn.logistics.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(UserManagementIntegrationTest.Config.class)
class UserManagementIntegrationTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource ds(){return new DriverManagerDataSource("jdbc:h2:mem:user-tests;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean DataSourceTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean AccountRepository accounts(JdbcTemplate jdbc){return new AccountRepository(jdbc);}
        @Bean TransportBusinessService business(JdbcTemplate jdbc){return new TransportBusinessService(jdbc,new ObjectMapper());}
        @Bean UserManagementService users(JdbcTemplate jdbc,AccountRepository accounts,TransportBusinessService business){return new UserManagementService(jdbc,accounts,business);}
        @Bean AuthService auth(AccountRepository accounts){return new AuthService(accounts,"");}
    }
    @Autowired JdbcTemplate jdbc; @Autowired DataSource ds;
    @Autowired UserManagementService users; @Autowired AccountRepository accounts; @Autowired AuthService auth;
    final UserPrincipal root=new UserPrincipal(100,"root","Root",UserRole.SUPER_ADMIN);
    final UserPrincipal admin=new UserPrincipal(101,"admin","Admin",UserRole.ADMIN);
    final UserPrincipal dispatcher=new UserPrincipal(102,"dispatch","Dispatch",UserRole.DISPATCHER);
    @BeforeEach void seed(){
        jdbc.execute("DROP ALL OBJECTS");new ResourceDatabasePopulator(new ClassPathResource("business-schema.sql")).execute(ds);
        jdbc.update("INSERT INTO users(id,username,display_name,role,account_status) VALUES(100,'root','Root','SUPER_ADMIN','APPROVED'),(101,'admin','Admin','ADMIN','APPROVED'),(102,'dispatch','Dispatch','DISPATCHER','APPROVED'),(103,'driver','Driver','DRIVER','APPROVED'),(104,'pending','Pending','DRIVER','PENDING')");
    }
    @Test void employeeCreationHashesPasswordAndBlocksPrivilegeEscalation(){
        var input=new UserManagementService.StaffInput("staff_new","test-password-1024","Staff",null,UserRole.DISPATCHER);
        assertThrows(ForbiddenException.class,()->users.createStaff(input,admin));
        long id=users.createStaff(input,root);
        assertTrue(PasswordHasher.verify(input.password(),accounts.findById(id).orElseThrow().passwordHash()));
        assertThrows(IllegalArgumentException.class,()->users.createStaff(new UserManagementService.StaffInput("illegal","test-password-1024","Staff",null,UserRole.SUPER_ADMIN),root));
        assertTrue(users.users(dispatcher).stream().allMatch(u->u.role().equals("DRIVER")));
        assertThrows(ForbiddenException.class,()->users.contact(101,new UserManagementService.ContactInput("Changed",null),dispatcher));
        users.contact(103,new UserManagementService.ContactInput("Updated driver","Contact"),admin);
        assertEquals("Updated driver",accounts.findById(103).orElseThrow().displayName());
    }
    @Test void roleAndStatusChangesRevokeSessionsWithoutBypassingReview(){
        accounts.insertSession("old-token",102,java.time.Instant.now().plusSeconds(100));
        users.role(102,new UserManagementService.RoleInput(UserRole.ADMIN),root);
        assertEquals("ADMIN",accounts.findById(102).orElseThrow().role());
        assertTrue(accounts.findPrincipalBySessionHash("old-token",java.time.Instant.now()).isEmpty());
        users.status(102,new UserManagementService.StatusInput(false),root);
        assertEquals("DISABLED",accounts.findById(102).orElseThrow().status());
        users.status(102,new UserManagementService.StatusInput(true),root);
        assertThrows(IllegalStateException.class,()->users.status(104,new UserManagementService.StatusInput(true),root));
        assertThrows(IllegalArgumentException.class,()->users.role(100,new UserManagementService.RoleInput(UserRole.ADMIN),root));
    }
    @Test void activeTasksBlockDriverDisablingAndDeletion(){
        jdbc.update("INSERT INTO transport_tasks(task_code,driver_id,task_status) VALUES('TEST-ONLY',103,'IN_TRANSIT')");
        assertThrows(IllegalStateException.class,()->users.status(103,new UserManagementService.StatusInput(false),root));
        assertThrows(IllegalStateException.class,()->auth.deleteUser(103,100));
        assertEquals("APPROVED",accounts.findById(103).orElseThrow().status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));
    }

    @Test void directorySeparatesApplicationsAndBlocksEditingThem(){
        jdbc.update("INSERT INTO users(id,username,display_name,role,account_status) VALUES(105,'rejected','Rejected','DRIVER','REJECTED'),(106,'disabled','Disabled','DRIVER','DISABLED'),(107,'deleted','Deleted','DRIVER','DELETED')");
        for (UserPrincipal actor : new UserPrincipal[]{root,admin,dispatcher}) {
            var visible=users.users(actor).stream().map(UserManagementService.UserView::id).toList();
            assertTrue(visible.containsAll(java.util.List.of(103L,106L)));
            assertFalse(visible.contains(104L)); assertFalse(visible.contains(105L)); assertFalse(visible.contains(107L));
            assertThrows(IllegalStateException.class,()->users.contact(105,new UserManagementService.ContactInput("Changed",null),actor));
            assertThrows(IllegalStateException.class,()->users.contact(104,new UserManagementService.ContactInput("Changed",null),actor));
        }
        assertEquals("Rejected",accounts.findById(105).orElseThrow().displayName());
    }

    @Test void passwordRecoveryRevokesSessionsAndRequiresReplacingTemporaryPassword(){
        String oldPassword="original-driver-password", newPassword="new-private-driver-password";
        jdbc.update("UPDATE users SET password_hash=? WHERE id=103",PasswordHasher.hash(oldPassword));
        var oldSession=auth.login("driver",oldPassword);
        var secondSession=auth.login("driver",oldPassword);
        var driver=oldSession.user();
        assertThrows(ForbiddenException.class,()->auth.resetDriverPassword(103,admin));
        assertThrows(ForbiddenException.class,()->auth.resetDriverPassword(103,dispatcher));
        assertThrows(ForbiddenException.class,()->auth.resetDriverPassword(103,driver));
        String temporary=auth.resetDriverPassword(103,root);
        assertTrue(temporary.length()>=20);
        assertTrue(auth.findPrincipal(oldSession.token()).isEmpty());
        assertTrue(auth.findPrincipal(secondSession.token()).isEmpty());
        assertThrows(AuthenticationException.class,()->auth.login("driver",oldPassword));
        var tempSession=auth.login("driver",temporary);
        assertTrue(tempSession.user().passwordChangeRequired());
        assertTrue(auth.findPrincipal(tempSession.token()).orElseThrow().passwordChangeRequired());
        var secondTempSession=auth.login("driver",temporary);
        assertThrows(IllegalArgumentException.class,()->auth.changePassword(tempSession.user(),"wrong-current",newPassword));
        assertThrows(IllegalArgumentException.class,()->auth.changePassword(tempSession.user(),temporary,"short"));
        assertThrows(IllegalArgumentException.class,()->auth.changePassword(tempSession.user(),temporary,temporary));
        assertTrue(auth.findPrincipal(tempSession.token()).isPresent());
        assertTrue(accounts.findById(103).orElseThrow().passwordChangeRequired());
        auth.changePassword(tempSession.user(),temporary,newPassword);
        assertTrue(auth.findPrincipal(tempSession.token()).isEmpty());
        assertTrue(auth.findPrincipal(secondTempSession.token()).isEmpty());
        assertThrows(AuthenticationException.class,()->auth.login("driver",temporary));
        assertFalse(auth.login("driver",newPassword).user().passwordChangeRequired());
        assertTrue(PasswordHasher.verify(newPassword,accounts.findById(103).orElseThrow().passwordHash()));
        assertEquals("APPROVED",accounts.findById(103).orElseThrow().status());
        String audit=jdbc.queryForList("SELECT details_json FROM audit_logs").toString();
        assertFalse(audit.contains(temporary)); assertFalse(audit.contains(newPassword)); assertFalse(audit.contains(oldPassword));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='DRIVER_PASSWORD_RESET'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='PASSWORD_CHANGED'",Integer.class));
    }

    @Test void resetCannotApproveRejectedPendingDeletedOrDisabledAccounts(){
        for (String state : new String[]{"PENDING","REJECTED","DELETED"}) {
            jdbc.update("UPDATE users SET account_status=? WHERE id=104",state);
            assertThrows(IllegalStateException.class,()->auth.resetDriverPassword(104,root));
            assertEquals(state,accounts.findById(104).orElseThrow().status());
        }
        assertThrows(IllegalArgumentException.class,()->auth.resetDriverPassword(101,root));
        jdbc.update("UPDATE users SET account_status='DISABLED' WHERE id=103");
        String temporary=auth.resetDriverPassword(103,root);
        assertEquals("DISABLED",accounts.findById(103).orElseThrow().status());
        assertThrows(AuthenticationException.class,()->auth.login("driver",temporary));
    }

    @Test void normalPasswordChangeVerifiesOldPasswordAndRevokesOtherDevices(){
        String old="staff-original-password", next="staff-replacement-password";
        jdbc.update("UPDATE users SET password_hash=? WHERE id=102",PasswordHasher.hash(old));
        var first=auth.login("dispatch",old);var second=auth.login("dispatch",old);
        assertThrows(IllegalArgumentException.class,()->auth.changePassword(first.user(),"wrong-password",next));
        assertTrue(auth.findPrincipal(second.token()).isPresent());
        auth.changePassword(first.user(),old,next);
        assertTrue(auth.findPrincipal(first.token()).isEmpty());assertTrue(auth.findPrincipal(second.token()).isEmpty());
        assertFalse(auth.login("dispatch",next).user().passwordChangeRequired());
    }
}
