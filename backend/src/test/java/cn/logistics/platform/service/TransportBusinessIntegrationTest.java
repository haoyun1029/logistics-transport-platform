package cn.logistics.platform.service;

import cn.logistics.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(TransportBusinessIntegrationTest.Config.class)
class TransportBusinessIntegrationTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:business-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000","sa","");}
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean DataSourceTransactionManager transactions(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean TransportBusinessService business(JdbcTemplate jdbc,ObjectMapper mapper){return new TransportBusinessService(jdbc,mapper);}
        @Bean RouteManagementService routes(JdbcTemplate jdbc,ObjectMapper mapper,TransportBusinessService business){return new RouteManagementService(jdbc,mapper,business);}
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource ds;
    @Autowired TransportBusinessService business;
    @Autowired RouteManagementService routes;
    final UserPrincipal dispatcher=new UserPrincipal(1,"dispatch","Dispatch",UserRole.DISPATCHER);
    final UserPrincipal driver=new UserPrincipal(2,"driver","Driver",UserRole.DRIVER);
    final UserPrincipal other=new UserPrincipal(3,"other","Other driver",UserRole.DRIVER);
    final UserPrincipal admin=new UserPrincipal(4,"admin","Admin",UserRole.ADMIN);

    @BeforeEach void seedIsolatedSyntheticDatabase(){
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("business-schema.sql")).execute(ds);
        jdbc.update("INSERT INTO users(id,username,display_name,role,account_status) VALUES(1,'dispatch','Dispatch','DISPATCHER','APPROVED'),(2,'driver','Driver','DRIVER','APPROVED'),(3,'other','Other','DRIVER','APPROVED'),(4,'admin','Admin','ADMIN','APPROVED')");
        jdbc.update("INSERT INTO vehicles(id,plate_number,vehicle_type,max_load_tons,fuel_model_liters_per_100km) VALUES(1,'TEST-ONLY-01','TEST',10,30),(2,'TEST-ONLY-02','TEST',10,30)");
        jdbc.update("INSERT INTO routes(id,route_name,origin_city,destination_city,route_status,data_source,version_number,created_by) VALUES(1,'SYNTHETIC TEST ONLY','A','B','VERIFIED','ISOLATED_TEST',1,4)");
        jdbc.update("INSERT INTO route_segments(route_id,sequence_number,from_city,to_city,road_name,road_type,distance_km,estimated_minutes,geometry_json,map_source,map_request_json,map_response_reference,truck_access_status,truck_evidence_source,truck_evidence_reference,verified_at) VALUES(1,1,'A','B','SYNTHETIC','TEST',110,100,?,'TEST','{}','test-response','VERIFIED','ISOLATED TEST','https://example.invalid/test',CURRENT_TIMESTAMP)","{\"coordinateSystem\":\"WGS84\",\"coordinates\":[[110,30],[111,30]]}");
    }
    @Test void persistsLifecycleScopesTasksAndNeverTrustsClientMatching(){
        var task=business.create(1,dispatcher);
        assertEquals("PENDING_ASSIGNMENT",task.status());
        business.assign(task.id(),2,1,dispatcher);
        assertTrue(business.tasks(other).isEmpty());
        assertThrows(ForbiddenException.class,()->business.accept(task.id(),other));
        business.accept(task.id(),driver);
        jdbc.update("UPDATE transport_tasks SET started_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)),task.id());
        var result=business.complete(task.id(),new TransportBusinessService.Completion(100.0,25.0,"已送达"),driver);
        assertEquals("COMPLETED",result.status());assertNotNull(result.startedAt());assertNotNull(result.completedAt());
        assertEquals("PENDING_MATCH",result.sampleStatus());assertEquals("已送达",result.comment());
        assertThrows(IllegalStateException.class,()->business.complete(task.id(),new TransportBusinessService.Completion(100.0,25.0,null),driver));
        var newService=new TransportBusinessService(jdbc,new ObjectMapper());
        assertEquals("COMPLETED",newService.task(task.id(),driver).status());
        assertEquals("AVAILABLE",business.vehicles(dispatcher).getFirst().status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM driver_feedback",Integer.class));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs",Integer.class));
    }
    @Test void persistsPerKilometerRateForDriverEarningsAndRejectsMissingRate(){
        var task=business.create(1,"收费测试货物",2.5,4.25,java.time.LocalDateTime.now().plusDays(1),"只验证单价持久化。",dispatcher);
        assertEquals(4.25,task.pricePerKm());
        business.assign(task.id(),2,1,dispatcher);
        business.accept(task.id(),driver);
        jdbc.update("UPDATE transport_tasks SET started_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)),task.id());
        var completed=business.complete(task.id(),new TransportBusinessService.Completion(110.5,30.0,"rate fixture"),driver);
        assertEquals(4.25,completed.pricePerKm());
        assertEquals(110.5,completed.actualDistanceKm());
        assertThrows(IllegalArgumentException.class,()->business.create(1,"Missing rate",1.0,null,java.time.LocalDateTime.now().plusDays(1),"rate required",dispatcher));
        assertThrows(IllegalArgumentException.class,()->business.create(1,"Zero rate",1.0,0.0,java.time.LocalDateTime.now().plusDays(1),"rate required",dispatcher));
        assertThrows(IllegalArgumentException.class,()->business.create(1,"Sub-cent rate",1.0,0.001,java.time.LocalDateTime.now().plusDays(1),"rate precision",dispatcher));
    }
    @Test void historicalRateBackfillRequiresSourceAndPreservesExistingRates(){
        var task=business.create(1,dispatcher);
        business.assign(task.id(),2,1,dispatcher);
        business.accept(task.id(),driver);
        jdbc.update("UPDATE transport_tasks SET started_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)),task.id());
        business.complete(task.id(),new TransportBusinessService.Completion(100.0,25.0,"historical rate fixture"),driver);
        jdbc.update("UPDATE transport_tasks SET price_per_km=NULL WHERE id=?",task.id());
        assertThrows(IllegalArgumentException.class,()->business.addHistoricalRate(task.id(),new TransportBusinessService.HistoricalRate(5.5," "),dispatcher));
        assertNull(business.task(task.id(),dispatcher).pricePerKm());
        assertThrows(ForbiddenException.class,()->business.addHistoricalRate(task.id(),new TransportBusinessService.HistoricalRate(5.5,"票据 2026-001"),driver));
        var updated=business.addHistoricalRate(task.id(),new TransportBusinessService.HistoricalRate(5.5,"运单与结算单 2026-001"),dispatcher);
        assertEquals(5.5,updated.pricePerKm());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='TASK_RATE_BACKFILLED'",Integer.class));
        assertThrows(IllegalStateException.class,()->business.addHistoricalRate(task.id(),new TransportBusinessService.HistoricalRate(7.0,"另一个凭据"),admin));
        assertEquals(5.5,business.task(task.id(),dispatcher).pricePerKm());
    }
    @Test void incompleteLegacyTaskCanBeCorrectedBeforeAssignment(){
        jdbc.update("INSERT INTO transport_tasks(task_code,route_id,task_status,source_type,created_by) VALUES('LEGACY-PENDING',1,'PENDING_ASSIGNMENT','SIMULATED',1)");
        long id=jdbc.queryForObject("SELECT id FROM transport_tasks WHERE task_code='LEGACY-PENDING'",Long.class);
        assertThrows(IllegalStateException.class,()->business.assign(id,2,1,dispatcher));
        var corrected=business.updatePendingTaskDetails(id,new TransportBusinessService.TaskDetails(
                "历史任务补录",2.5,4.25,java.time.LocalDateTime.now().plusDays(1),"根据原始运单补充的资料。"),dispatcher);
        assertEquals(110.0,corrected.plannedDistanceKm());
        assertEquals(4.25,corrected.pricePerKm());
        business.assign(id,2,1,dispatcher);
        assertEquals("PENDING_ACCEPTANCE",business.task(id,dispatcher).status());
    }
    @Test void issueFeedbackIsScopedToActiveDriverTaskAndAudited(){
        var task=business.create(1,dispatcher);
        business.assign(task.id(),2,1,dispatcher);
        business.reportIssue(task.id(),new TransportBusinessService.TaskIssue(
                "TIME_EXTENSION","计划时间太短，申请延长到明天 18:00。",java.time.LocalDateTime.now().plusDays(1)),driver);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='TASK_ISSUE_FEEDBACK'",Integer.class));
        assertThrows(ForbiddenException.class,()->business.reportIssue(task.id(),new TransportBusinessService.TaskIssue(
                "ACCIDENT","无权提交",null),other));
        business.accept(task.id(),driver);
        assertThrows(IllegalArgumentException.class,()->business.reportIssue(task.id(),new TransportBusinessService.TaskIssue(
                "UNKNOWN","类型错误",null),driver));
    }
    @Test void unverifiedRouteCannotBeAssignedAndEmptyGeometryCannotBypassGate(){
        jdbc.update("UPDATE routes SET route_status='PENDING_VERIFICATION' WHERE id=1");
        var task=business.create(1,dispatcher);
        assertThrows(IllegalStateException.class,()->business.assign(task.id(),2,1,dispatcher));
        assertEquals("PENDING_ASSIGNMENT",business.task(task.id(),dispatcher).status());
        jdbc.update("UPDATE routes SET route_status='VERIFIED' WHERE id=1");jdbc.update("UPDATE route_segments SET geometry_json='{}'");
        assertThrows(IllegalArgumentException.class,()->business.assign(task.id(),2,1,dispatcher));
    }
    @Test void concurrentAssignmentCannotReserveSameDriverTwice() throws Exception {
        var first=business.create(1,dispatcher);var second=business.create(1,dispatcher);
        var executor=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try {
            var futures=executor.invokeAll(List.of(
                    ()->{barrier.await();return assignResult(first.id(),1);},
                    ()->{barrier.await();return assignResult(second.id(),2);}
            ));
            int successes=0;for(var future:futures)if(Boolean.TRUE.equals(future.get(10,TimeUnit.SECONDS)))successes++;
            assertEquals(1,successes);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM transport_tasks WHERE task_status='PENDING_ACCEPTANCE'",Integer.class));
        } finally {executor.shutdownNow();}
    }
    private boolean assignResult(long task,long vehicle){try{business.assign(task,2,vehicle,dispatcher);return true;}catch(IllegalStateException e){return false;}}
    @Test void auditFailureRollsBackBusinessWrite(){
        jdbc.execute("ALTER TABLE audit_logs RENAME TO unavailable_audit");
        assertThrows(org.springframework.dao.DataAccessException.class,()->business.create(1,dispatcher));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM transport_tasks",Integer.class));
    }
    @Test void reservationsReleaseOnCancellationAndMaintenanceCannotStealVehicle(){
        var task=business.create(1,dispatcher);business.assign(task.id(),2,1,dispatcher);
        assertEquals("RESERVED",business.vehicles(dispatcher).getFirst().status());
        assertThrows(IllegalStateException.class,()->business.vehicleStatus(1,"MAINTENANCE",admin));
        business.cancel(task.id(),dispatcher);business.vehicleStatus(1,"MAINTENANCE",admin);
        assertEquals("MAINTENANCE",business.vehicles(dispatcher).getFirst().status());
    }
    @Test void adminSetsReminderAndAssignedDriverCanOnlyReadIt(){
        var task=business.create(1,dispatcher);
        business.assign(task.id(),2,1,dispatcher);
        var date=java.time.LocalDate.of(2027,1,20);
        business.maintenanceReminder(1,date,admin);
        assertEquals(date,business.vehicles(driver).getFirst().nextServiceOn());
        assertThrows(ForbiddenException.class,()->business.maintenanceReminder(1,date,driver));
        business.maintenanceReminder(1,null,admin);
        assertNull(business.vehicles(driver).getFirst().nextServiceOn());
    }
    @Test void recordingMaintenanceSuggestsNextDateAndReleasesVehicle(){
        business.vehicleStatus(1,"MAINTENANCE",admin);
        var servicedOn=java.time.LocalDate.now().minusDays(1);
        var id=business.recordMaintenance(new TransportBusinessService.MaintenanceInput(
                1,"更换机油并检查制动系统",servicedOn,null,90),admin);
        assertTrue(id>0);
        assertEquals(servicedOn.plusDays(90),business.vehicles(admin).getFirst().nextServiceOn());
        assertEquals("AVAILABLE",business.vehicles(admin).getFirst().status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_records",Integer.class));
        assertThrows(IllegalArgumentException.class,()->business.recordMaintenance(new TransportBusinessService.MaintenanceInput(
                1,"无效周期",servicedOn,null,0),admin));
    }
    @Test void invalidFeedbackDoesNotCompleteTaskAndUnreasonableFeedbackRemainsInvalid(){
        var task=business.create(1,dispatcher);business.assign(task.id(),2,1,dispatcher);business.accept(task.id(),driver);
        assertThrows(IllegalArgumentException.class,()->business.complete(task.id(),new TransportBusinessService.Completion(Double.NaN,20.0,null),driver));
        assertEquals("IN_TRANSIT",business.task(task.id(),driver).status());
        var completed=business.complete(task.id(),new TransportBusinessService.Completion(1.0,10.0,"异常测试"),driver);
        assertEquals("INVALID",completed.sampleStatus());assertNotNull(completed.exclusionReason());
    }
    @Test void failedPublishRollsBackStatusAndPublishedRouteRequiresNewVersion(){
        jdbc.update("UPDATE routes SET route_status='PENDING_VERIFICATION' WHERE id=1");
        jdbc.update("UPDATE route_segments SET truck_access_status='PENDING'");
        assertThrows(IllegalStateException.class,()->routes.publish(1,admin));
        assertEquals("PENDING_VERIFICATION",routes.route(1).verificationStatus());
        jdbc.update("UPDATE routes SET route_status='VERIFIED' WHERE id=1");
        long version=routes.copyVersion(1,admin);
        assertEquals("VERIFIED",routes.route(1).verificationStatus());assertEquals(2,routes.route(version).version());
        assertEquals("PENDING",routes.route(version).segments().getFirst().truckAccessStatus());
    }
    @Test void impossibleElapsedTimeIsRetainedAsInvalidFeedback(){
        var task=business.create(1,dispatcher);business.assign(task.id(),2,1,dispatcher);business.accept(task.id(),driver);
        // Pin the start far enough in the past to make this a deterministic speed-bound test.
        jdbc.update("UPDATE transport_tasks SET started_at=? WHERE id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)),task.id());
        var completed=business.complete(task.id(),new TransportBusinessService.Completion(100.0,25.0,"accelerated test"),driver);
        assertEquals("COMPLETED",completed.status());assertEquals("INVALID",completed.sampleStatus());
        assertTrue(completed.exclusionReason().contains("系统运输耗时"));
        assertEquals(100,completed.actualDistanceKm());assertEquals("AVAILABLE",business.vehicles(dispatcher).getFirst().status());
    }
}
