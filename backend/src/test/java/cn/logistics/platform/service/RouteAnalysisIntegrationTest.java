package cn.logistics.platform.service;

import cn.logistics.platform.domain.RoadGeometry;
import cn.logistics.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(RouteAnalysisIntegrationTest.Config.class)
class RouteAnalysisIntegrationTest {
    @Configuration @Import(TransportBusinessIntegrationTest.Config.class) static class Config {
        @Bean MongoTelemetryService mongo(){return mock(MongoTelemetryService.class);}
        @Bean KafkaTemplate<String,Object> kafka(){return mock(KafkaTemplate.class);}
        @Bean SystemPolicyService policy(JdbcTemplate jdbc,ObjectMapper json,TransportBusinessService b){return new SystemPolicyService(jdbc,json,b);}
        @Bean TaskMatchingService matching(TransportBusinessService b,RouteManagementService r,MongoTelemetryService m,JdbcTemplate jdbc,ObjectMapper json,SystemPolicyService p){return new TaskMatchingService(b,r,m,jdbc,json,p);}
        @Bean RouteOptimizationService optimization(JdbcTemplate jdbc,ObjectMapper json,TransportBusinessService b,RouteManagementService r,SystemPolicyService p){return new RouteOptimizationService(jdbc,json,b,r,p);}
        @Bean BusinessGpsService gps(TransportBusinessService b,RouteManagementService r,MongoTelemetryService m,SystemPolicyService p,JdbcTemplate jdbc,ObjectMapper json,KafkaTemplate<String,Object> kafka){return new BusinessGpsService(b,r,m,p,jdbc,json,kafka,"TEST-ONLY");}
    }
    @Autowired DataSource ds; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired TransportBusinessService business; @Autowired RouteManagementService routes;
    @Autowired TaskMatchingService matching; @Autowired RouteOptimizationService optimization;
    @Autowired MongoTelemetryService mongo; @Autowired KafkaTemplate<String,Object> kafka; @Autowired BusinessGpsService gps; @Autowired SystemPolicyService policy;
    final UserPrincipal admin=new UserPrincipal(1,"admin","Admin",UserRole.ADMIN),driver=new UserPrincipal(2,"driver","Driver",UserRole.DRIVER),dispatcher=new UserPrincipal(3,"dispatch","Dispatch",UserRole.DISPATCHER);
    @BeforeEach void seed(){
        reset(mongo,kafka);when(mongo.simulationCheck(anyString())).thenReturn(new MongoTelemetryService.SimulationCheck("CONNECTED",null,0));jdbc.execute("DROP ALL OBJECTS");new ResourceDatabasePopulator(new ClassPathResource("business-schema.sql")).execute(ds);
        jdbc.update("INSERT INTO users(id,username,display_name,role,account_status) VALUES(1,'admin','Admin','ADMIN','APPROVED'),(2,'driver','Driver','DRIVER','APPROVED'),(3,'dispatch','Dispatch','DISPATCHER','APPROVED')");
        jdbc.update("INSERT INTO vehicles(id,plate_number,vehicle_type,max_load_tons,fuel_model_liters_per_100km) VALUES(1,'TEST-ONLY','TEST',10,30)");
        jdbc.update("INSERT INTO routes(id,route_name,origin_city,destination_city,route_status,data_source,created_by) VALUES(1,'SYNTHETIC PLAN','A','B','VERIFIED','TEST',1),(2,'SYNTHETIC ALTERNATIVE','A','B','VERIFIED','TEST',1)");
        segment(1,1,"A","B",110,120,"[[110,30],[111,30]]");
        segment(2,1,"A","C",45,45,"[[110,30],[110.5,30.1]]");segment(2,2,"C","B",45,45,"[[110.5,30.1],[111,30]]");
    }
    void segment(long route,int sequence,String from,String to,double distance,double minutes,String coordinates){
        jdbc.update("INSERT INTO route_segments(route_id,sequence_number,from_city,to_city,road_name,road_type,distance_km,estimated_minutes,geometry_json,map_source,map_request_json,map_response_reference,truck_access_status,truck_evidence_source,truck_evidence_reference,verified_at) VALUES(?,?,?,?, 'ISOLATED SYNTHETIC','TEST',?,?,?,'TEST','{}','test-reference','VERIFIED','ISOLATED TEST','https://example.invalid/test',CURRENT_TIMESTAMP)",route,sequence,from,to,distance,minutes,"{\"coordinateSystem\":\"WGS84\",\"coordinates\":"+coordinates+"}");
    }
    @Test void roadSimulationMatchingAndVersionedSameSourceScoringFormOnePersistentWorkflow()throws Exception {
        var task=business.create(1,dispatcher);business.assign(task.id(),2,1,dispatcher);business.accept(task.id(),driver);
        when(kafka.send(anyString(),anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        var simulation=gps.start(task.id(),2L,driver);
        var capture=ArgumentCaptor.forClass(Object.class);verify(kafka,times(simulation.publishedCount())).send(eq("TEST-ONLY"),eq(task.code()),capture.capture());
        var points=new ArrayList<MongoTelemetryService.MapPoint>();int n=0;
        for(var raw:capture.getAllValues()){
            var event=json.valueToTree(raw);assertFalse(event.has("matchedCity"));assertFalse(event.has("matchedRoad"));assertFalse(event.has("roadSequence"));assertEquals("SIMULATED",event.path("sourceType").asText());
            points.add(new MongoTelemetryService.MapPoint("test-event-"+(++n),n,event.path("latitude").asDouble(),event.path("longitude").asDouble(),event.path("speedKmh").asDouble(),event.path("timestamp").asText(),Instant.now().toString(),"SIMULATED","NOT_MATCHED",List.of(),simulation.runId(),simulation.publishedCount(),"WGS84"));
        }
        when(mongo.trajectory(task.code())).thenReturn(new MongoTelemetryService.Trajectory(task.code(),"CONNECTED","FLINK_MONGODB",Instant.now(),false,points,"ISOLATED TEST"));
        jdbc.update("UPDATE transport_tasks SET started_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(7200)),task.id());
        business.complete(task.id(),new TransportBusinessService.Completion(100.0,30.0,"Test feedback"),driver);
        var analysis=matching.analyse(task.id(),admin);assertEquals("VALID",analysis.path("sampleStatus").asText());assertEquals(2,analysis.path("matchedRoute").path("databaseId").asLong());assertTrue(analysis.path("cityDifference").path("different").asBoolean());
        var evaluation=optimization.evaluate(1,admin);assertEquals("PRELIMINARY",evaluation.snapshot().path("status").asText());assertEquals(1,evaluation.snapshot().path("validSampleCount").asInt());
        assertEquals("ESTIMATED",evaluation.snapshot().path("fuelValueType").asText());assertEquals(2,evaluation.snapshot().path("scores").size());
        assertEquals(0,evaluation.snapshot().path("scores").get(0).path("compositeScore").asDouble());assertEquals(100,evaluation.snapshot().path("scores").get(1).path("compositeScore").asDouble());
        assertEquals("VERIFIED",routes.route(1).verificationStatus());assertEquals("CANDIDATE",evaluation.snapshot().path("candidate").path("verificationStatus").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM route_optimization_runs",Integer.class));assertEquals(1,optimization.history().size());
    }
    @Test void explicitDemoModeRunsPendingRouteWorkflowWithoutCreatingFormalRecommendation()throws Exception {
        jdbc.update("UPDATE routes SET route_status='PENDING_VERIFICATION' WHERE id IN (1,2)");
        jdbc.update("UPDATE route_segments SET truck_access_status='PENDING',truck_evidence_source=NULL,truck_evidence_reference=NULL,verified_at=NULL,verified_by=NULL WHERE route_id IN (1,2)");

        var demoBusiness=new TransportBusinessService(jdbc,json,true);
        var demoRoutes=new RouteManagementService(jdbc,json,demoBusiness);
        var demoPolicy=new SystemPolicyService(jdbc,json,demoBusiness);
        var demoGps=new BusinessGpsService(demoBusiness,demoRoutes,mongo,demoPolicy,jdbc,json,kafka,"TEST-ONLY");
        var demoMatching=new TaskMatchingService(demoBusiness,demoRoutes,mongo,jdbc,json,demoPolicy);
        var demoOptimization=new RouteOptimizationService(jdbc,json,demoBusiness,demoRoutes,demoPolicy);

        var task=demoBusiness.create(1,dispatcher);
        assertTrue(task.demoMode());
        demoBusiness.assign(task.id(),2,1,dispatcher);demoBusiness.accept(task.id(),driver);
        when(kafka.send(anyString(),anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        // The driver supplies no route choice. DEMO_MODE selects the same-OD
        // alternative on the server so matching can recover it from GPS only.
        var simulation=demoGps.start(task.id(),null,driver);
        assertTrue(simulation.demoMode());assertTrue(simulation.note().contains("DEMO_MODE"));
        var capture=ArgumentCaptor.forClass(Object.class);verify(kafka,times(simulation.publishedCount())).send(eq("TEST-ONLY"),eq(task.code()),capture.capture());
        var points=new ArrayList<MongoTelemetryService.MapPoint>();int n=0;
        for(var raw:capture.getAllValues()){
            var event=json.valueToTree(raw);assertTrue(event.path("demoMode").asBoolean());
            assertEquals("DB-1",event.path("routeId").asText());
            assertFalse(event.path("plannedRoadContext").has("simulationRouteId"));
            points.add(new MongoTelemetryService.MapPoint("demo-event-"+(++n),n,event.path("latitude").asDouble(),event.path("longitude").asDouble(),event.path("speedKmh").asDouble(),event.path("timestamp").asText(),Instant.now().toString(),"SIMULATED","NOT_MATCHED",List.of(),simulation.runId(),simulation.publishedCount(),"WGS84"));
        }
        when(mongo.trajectory(task.code())).thenReturn(new MongoTelemetryService.Trajectory(task.code(),"CONNECTED","FLINK_MONGODB",Instant.now(),false,points,"ISOLATED DEMO TEST"));
        jdbc.update("UPDATE transport_tasks SET started_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(7200)),task.id());
        demoBusiness.complete(task.id(),new TransportBusinessService.Completion(100.0,30.0,"Demo feedback"),driver);

        var analysis=demoMatching.analyse(task.id(),admin);
        assertEquals("VALID",analysis.path("sampleStatus").asText());assertTrue(analysis.path("demoMode").asBoolean());
        assertFalse(analysis.path("formalOptimizationEligible").asBoolean());assertEquals(2,analysis.path("matchedRoute").path("databaseId").asLong());
        var simulationAudit=json.readTree(jdbc.queryForObject(
                "SELECT details_json FROM audit_logs WHERE action='ROAD_GPS_SIMULATION' AND entity_id=?",
                String.class,task.code()));
        assertEquals(1,simulationAudit.path("planRouteId").asLong());
        assertEquals(2,simulationAudit.path("actualRouteId").asLong());
        assertEquals("DEMO_PRECONFIGURED_SAME_OD_ALTERNATIVE",simulationAudit.path("scenarioType").asText());
        var evaluation=demoOptimization.evaluate(1,admin);
        assertEquals("DEMO_ONLY",evaluation.snapshot().path("status").asText());assertTrue(evaluation.snapshot().path("demoMode").asBoolean());
        assertEquals(0,evaluation.snapshot().path("validSampleCount").asInt());assertEquals(1,evaluation.snapshot().path("demoSampleCount").asInt());
        assertEquals(1,evaluation.snapshot().path("completedTaskCount").asInt());
        assertEquals(1,evaluation.snapshot().path("latestAnalysisCount").asInt());
        assertEquals(1,evaluation.snapshot().path("eligibleSampleCount").asInt());
        assertEquals("DEMO_ONLY",evaluation.snapshot().path("candidate").path("estimateSource").asText());
        long candidateId=evaluation.snapshot().path("candidate").path("databaseId").asLong();
        assertThrows(IllegalStateException.class,()->demoRoutes.publish(candidateId,admin));
        assertEquals("CANDIDATE",demoRoutes.route(candidateId).verificationStatus());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM route_optimization_runs",Integer.class));
        assertThrows(IllegalStateException.class,()->demoBusiness.requireVerifiedRoute(1));
    }
    @Test void formalSimulationKeepsAssignedPlanWhenNoScenarioIsConfigured() throws Exception {
        var task=business.create(1,dispatcher);business.assign(task.id(),2,1,dispatcher);business.accept(task.id(),driver);
        when(kafka.send(anyString(),anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));

        var simulation=gps.start(task.id(),null,driver);

        var capture=ArgumentCaptor.forClass(Object.class);
        verify(kafka,times(simulation.publishedCount())).send(eq("TEST-ONLY"),eq(task.code()),capture.capture());
        assertEquals("DB-1",json.valueToTree(capture.getValue()).path("routeId").asText());
        var simulationAudit=json.readTree(jdbc.queryForObject(
                "SELECT details_json FROM audit_logs WHERE action='ROAD_GPS_SIMULATION' AND entity_id=?",
                String.class,task.code()));
        assertEquals(1,simulationAudit.path("planRouteId").asLong());
        assertEquals(1,simulationAudit.path("actualRouteId").asLong());
        assertEquals("ASSIGNED_PLAN_ROUTE",simulationAudit.path("scenarioType").asText());
    }
    @Test void completeExistingSimulationIsReusedWithoutPublishingDuplicateGps() {
        var task=business.create(1,dispatcher);business.assign(task.id(),2,1,dispatcher);business.accept(task.id(),driver);
        when(mongo.simulationCheck(task.code())).thenReturn(new MongoTelemetryService.SimulationCheck("CONNECTED","existing-run",91));

        var simulation=gps.start(task.id(),null,driver);

        assertTrue(simulation.reusedExistingRun());assertEquals("existing-run",simulation.runId());assertEquals(0,simulation.publishedCount());
        verifyNoInteractions(kafka);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='ROAD_GPS_SIMULATION_DUPLICATE_SKIPPED'",Integer.class));
    }
    @Test void legacyFlagsAndUnverifiedRoutesDoNotCreateRecommendations(){
        jdbc.update("INSERT INTO transport_tasks(task_code,route_id,task_status,sample_status,route_difference_recoverable) VALUES('LEGACY-FIXTURE',1,'COMPLETED','VALID',TRUE)");
        var result=optimization.evaluate(1,admin);assertEquals("NO_RECOMMENDATION",result.snapshot().path("status").asText());assertEquals(0,result.snapshot().path("validSampleCount").asInt());
        assertEquals(1,result.snapshot().path("completedTaskCount").asInt());
        assertEquals(1,result.snapshot().path("taskValidFlagCount").asInt());
        assertEquals(0,result.snapshot().path("latestAnalysisCount").asInt());
        assertTrue(result.snapshot().path("reason").asText().contains("没有最新道路匹配分析记录"));
        jdbc.update("UPDATE routes SET route_status='PENDING_VERIFICATION' WHERE id=1");
        assertEquals("NO_RECOMMENDATION",optimization.evaluate(1,admin).snapshot().path("status").asText());assertEquals(2,optimization.history().size());
    }
    @Test void policyValidationAndOptimisticVersionAreEnforced(){
        var d=SystemPolicyService.Policy.defaults();assertEquals(0,policy.current().version());assertEquals(1,policy.save(d,admin).version());assertThrows(IllegalStateException.class,()->policy.save(d,admin));
        assertThrows(IllegalArgumentException.class,()->policy.save(new SystemPolicyService.Policy(1,.7,.3,.3,80,.15,.5,3,30),admin));
        assertThrows(ForbiddenException.class,()->policy.save(d,driver));
    }
    @Test void impossibleFeedbackIsExcludedBeforeMongoMatching(){
        var task=business.create(1,dispatcher);business.assign(task.id(),2,1,dispatcher);business.accept(task.id(),driver);
        business.complete(task.id(),new TransportBusinessService.Completion(100.0,25.0,"too fast"),driver);
        var analysis=matching.analyse(task.id(),admin);
        assertEquals("INVALID",analysis.path("sampleStatus").asText());
        assertTrue(analysis.path("reason").asText().contains("系统运输耗时"));
        verifyNoInteractions(mongo);
    }
    @Test void historicalValidFlagCannotBypassCurrentFeedbackPlausibility(){
        jdbc.update("INSERT INTO transport_tasks(id,task_code,route_id,task_status,sample_status,route_difference_recoverable) VALUES(1,'OLD-VALID',1,'COMPLETED','VALID',TRUE)");
        jdbc.update("INSERT INTO actual_route_analyses(task_id,sample_status,snapshot_json,created_by) VALUES(1,'VALID',?,1)",
                "{\"matcherVersion\":\""+TaskMatchingService.VERSION+"\",\"source\":\"FLINK_MONGODB\",\"actualDistanceKm\":100,\"actualFuelLiters\":25,\"actualElapsedMinutes\":0.25}");
        var result=optimization.evaluate(1,admin);
        assertEquals("NO_RECOMMENDATION",result.snapshot().path("status").asText());
        assertEquals(0,result.snapshot().path("validSampleCount").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM actual_route_analyses",Integer.class));
    }
}
