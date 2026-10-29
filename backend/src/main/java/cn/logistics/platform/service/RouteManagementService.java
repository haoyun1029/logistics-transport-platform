package cn.logistics.platform.service;

import cn.logistics.platform.domain.RoadGeometry;
import cn.logistics.platform.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static cn.logistics.platform.service.TransportBusinessService.*;

@Service
public class RouteManagementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransportBusinessService business;
    public RouteManagementService(JdbcTemplate jdbc,ObjectMapper json,TransportBusinessService business) { this.jdbc=jdbc;this.json=json;this.business=business; }
    public List<RouteView> routes() {
        return jdbc.query("SELECT * FROM routes ORDER BY id DESC",(rs,n)->{
            long id=rs.getLong("id");var segments=segments(id);List<String> cities=new ArrayList<>();
            if(!segments.isEmpty()){cities.add(segments.getFirst().fromCity());segments.forEach(s->cities.add(s.toCity()));}
            return new RouteView("DB-"+id,id,rs.getString("route_name"),rs.getInt("version_number"),rs.getObject("parent_route_id",Long.class),rs.getString("origin_city"),rs.getString("destination_city"),rs.getString("route_status"),rs.getString("data_source"),List.copyOf(cities),segments);
        });
    }
    public RouteView route(long id) { return routes().stream().filter(r->r.databaseId()==id).findFirst().orElseThrow(()->new IllegalArgumentException("路线不存在。")); }
    public List<SegmentView> segments(long id) {
        return jdbc.query("SELECT * FROM route_segments WHERE route_id=? ORDER BY sequence_number",(rs,n)->{
            var timestamp=rs.getTimestamp("verified_at");String geometry=rs.getString("geometry_json");
            return new SegmentView(rs.getLong("id"),rs.getInt("sequence_number"),rs.getString("from_city"),rs.getString("to_city"),rs.getString("road_name"),rs.getString("road_type"),rs.getDouble("distance_km"),rs.getDouble("estimated_minutes"),rs.getString("map_source"),rs.getString("map_request_json"),rs.getString("map_response_reference"),geometry,rs.getString("truck_access_status"),rs.getString("truck_evidence_source"),rs.getString("truck_evidence_reference"),timestamp==null?null:timestamp.toInstant());
        },id);
    }
    @Transactional
    public long create(RouteInput request,UserPrincipal actor) {
        String name=required(request.name(),120,"路线名称"),origin=required(request.originCity(),80,"起点城市"),destination=required(request.destinationCity(),80,"终点城市");
        if(origin.equals(destination)) throw new IllegalArgumentException("起终点不能相同。");
        String source=required(request.source(),80,"数据来源");int version=1;
        if(request.parentRouteId()!=null) {
            var parent=jdbc.queryForList("SELECT id,origin_city,destination_city FROM routes WHERE id=? FOR UPDATE",request.parentRouteId());
            if(parent.isEmpty() || !origin.equals(parent.getFirst().get("origin_city")) || !destination.equals(parent.getFirst().get("destination_city")))throw new IllegalArgumentException("父版本必须为同一 OD。");
            version=jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM routes WHERE origin_city=? AND destination_city=?",Integer.class,origin,destination);
        }
        long id=insert("INSERT INTO routes(route_name,origin_city,destination_city,route_status,data_source,version_number,parent_route_id,created_by) VALUES(?,?,?,'DRAFT',?,?,?,?)",name,origin,destination,source,version,request.parentRouteId(),actor.id());
        business.audit(actor.id(),"ROUTE_CREATED",Long.toString(id),Map.of("version",version));return id;
    }
    @Transactional
    public long saveSegment(long routeId,SegmentInput request,UserPrincipal actor) {
        lockDraft(routeId);
        if(request.sequence()<1 || request.sequence()>1000)throw new IllegalArgumentException("道路段顺序须为 1–1000。");
        String from=required(request.fromCity(),80,"出发城市"),to=required(request.toCity(),80,"到达城市");
        if(from.equals(to))throw new IllegalArgumentException("相邻城市不能相同。");
        String road=required(request.roadName(),160,"道路名称"),type=required(request.roadType(),48,"道路类型");
        positive(request.distanceKm(),50000,"路段里程");positive(request.estimatedMinutes(),100000,"路段耗时");
        String source=required(request.mapSource(),80,"地图来源"),reference=required(request.mapResponseReference(),512,"地图结果引用");
        String parameters=required(request.mapRequestJson(),12000,"脱敏地图请求 JSON");
        try { var node=json.readTree(parameters);if(!node.isObject() || containsSecret(node))throw new IllegalArgumentException("请提供不含密钥的地图请求对象。"); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("地图请求 JSON 无效。");}
        String geometry=optional(request.geometryJson(),1000000,"道路几何");
        if(geometry!=null)RoadGeometry.parse(geometry,json);
        var existing=jdbc.queryForList("SELECT id FROM route_segments WHERE route_id=? AND sequence_number=?",Long.class,routeId,request.sequence());
        long id;
        if(existing.isEmpty())id=insert("INSERT INTO route_segments(route_id,sequence_number,from_city,to_city,road_name,road_type,distance_km,estimated_minutes,geometry_json,map_source,map_request_json,map_response_reference) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",routeId,request.sequence(),from,to,road,type,request.distanceKm(),request.estimatedMinutes(),geometry,source,parameters,reference);
        else {
            id=existing.getFirst();
            jdbc.update("UPDATE route_segments SET from_city=?,to_city=?,road_name=?,road_type=?,distance_km=?,estimated_minutes=?,geometry_json=?,map_source=?,map_request_json=?,map_response_reference=?,truck_access_status='PENDING',truck_evidence_source=NULL,truck_evidence_reference=NULL,verified_at=NULL,verified_by=NULL WHERE id=?",from,to,road,type,request.distanceKm(),request.estimatedMinutes(),geometry,source,parameters,reference,id);
        }
        jdbc.update("UPDATE routes SET route_status='PENDING_VERIFICATION' WHERE id=?",routeId);
        business.audit(actor.id(),"ROUTE_SEGMENT_SAVED",Long.toString(id),Map.of("routeId",routeId,"sequence",request.sequence()));return id;
    }
    @Transactional
    public void verifySegment(long routeId,long segmentId,Verification request,UserPrincipal actor) {
        lockDraft(routeId);
        var segment=segments(routeId).stream().filter(s->s.id()==segmentId).findFirst().orElseThrow(()->new IllegalArgumentException("道路段不存在。"));
        String source=required(request.source(),160,"货车通行依据来源"),reference=required(request.reference(),512,"货车通行依据引用");
        String note=required(request.note(),1000,"核验说明");
        if(request.approve()==null)throw new IllegalArgumentException("请选择核验结论。");
        if(request.approve()) {
            if(segment.geometryJson()==null)throw new IllegalArgumentException("先补充道路几何，城市中心连线不能代替道路。");
            RoadGeometry.parse(segment.geometryJson(),json);
            if(!reference.startsWith("https://") && !reference.startsWith("http://"))throw new IllegalArgumentException("请填写可追溯的公开货车依据链接。");
            if(source.toLowerCase().contains("fixture") || source.toLowerCase().contains("demo"))throw new IllegalArgumentException("演示夹具不能作为货车通行依据。");
        }
        jdbc.update("UPDATE route_segments SET truck_access_status=?,truck_evidence_source=?,truck_evidence_reference=?,verified_at=CURRENT_TIMESTAMP(3),verified_by=? WHERE id=?",request.approve()?"VERIFIED":"REJECTED",source,reference,actor.id(),segmentId);
        business.audit(actor.id(),"ROUTE_SEGMENT_REVIEWED",Long.toString(segmentId),Map.of("routeId",routeId,"approve",request.approve(),"source",source,"reference",reference,"note",note));
    }
    @Transactional
    public void publish(long id,UserPrincipal actor) {
        lockDraft(id);
        String dataSource=jdbc.queryForObject("SELECT data_source FROM routes WHERE id=? FOR UPDATE",String.class,id);
        if("DEMO_ONLY".equals(dataSource))throw new IllegalStateException("演示候选不能直接发布；请创建新版本并重新完成逐段货车核验。");
        // Transaction rolls back this tentative status if any segment fails the shared gate.
        jdbc.update("UPDATE routes SET route_status='VERIFIED' WHERE id=?",id);
        business.requireVerifiedRoute(id);
        jdbc.update("UPDATE routes SET verified_at=CURRENT_TIMESTAMP(3),verified_by=? WHERE id=?",actor.id(),id);
        business.audit(actor.id(),"ROUTE_PUBLISHED",Long.toString(id),Map.of());
    }
    @Transactional
    public long copyVersion(long id,UserPrincipal actor) {
        var original=route(id);
        String source="DEMO_ONLY".equals(original.estimateSource())&&!original.segments().isEmpty()
                ?original.segments().getFirst().mapSource():original.estimateSource();
        long next=create(new RouteInput(original.name()+" 新版本",original.originCity(),original.destinationCity(),source,id),actor);
        for(var s:original.segments())saveSegment(next,new SegmentInput(s.sequence(),s.fromCity(),s.toCity(),s.roadName(),s.roadType(),s.distanceKm(),s.estimatedMinutes(),s.mapSource(),s.mapRequestJson(),s.mapResponseReference(),s.geometryJson()),actor);
        return next;
    }
    private void lockDraft(long id) {
        var statuses=jdbc.queryForList("SELECT route_status FROM routes WHERE id=? FOR UPDATE",String.class,id);
        if(statuses.isEmpty())throw new IllegalArgumentException("路线不存在。");
        if("VERIFIED".equals(statuses.getFirst()))throw new IllegalStateException("已发布路线不可覆盖，请创建新版本。");
    }
    private static boolean containsSecret(com.fasterxml.jackson.databind.JsonNode node){
        if(node.isObject()){var fields=node.fields();while(fields.hasNext()){var entry=fields.next();if(java.util.Set.of("key","apikey","api_key","token","password","securitycode").contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))||containsSecret(entry.getValue()))return true;}}
        else if(node.isArray())for(var value:node)if(containsSecret(value))return true;
        return false;
    }
    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }
    public record RouteInput(String name,String originCity,String destinationCity,String source,Long parentRouteId) {}
    public record SegmentInput(int sequence,String fromCity,String toCity,String roadName,String roadType,Double distanceKm,Double estimatedMinutes,String mapSource,String mapRequestJson,String mapResponseReference,String geometryJson) {}
    public record Verification(Boolean approve,String source,String reference,String note) {}
    public record RouteView(String id,long databaseId,String name,int version,Long parentRouteId,String originCity,String destinationCity,String verificationStatus,String estimateSource,List<String> citySequence,List<SegmentView> segments) {}
    public record SegmentView(long id,int sequence,String fromCity,String toCity,String roadName,String roadType,double distanceKm,double estimatedMinutes,String mapSource,String mapRequestJson,String mapResponseReference,String geometryJson,String truckAccessStatus,String truckEvidenceSource,String truckEvidenceReference,Instant verifiedAt) {}
}
