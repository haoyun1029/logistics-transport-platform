package cn.logistics.platform.service;

import cn.logistics.platform.security.ForbiddenException;
import cn.logistics.platform.domain.RoadGeometry;
import cn.logistics.platform.domain.FeedbackPlausibility;
import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.security.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MySQL business workflow. Demo fixtures never enter this service. */
@Service
public class TransportBusinessService {
    public static final int DEFAULT_MAINTENANCE_INTERVAL_DAYS = 90;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final boolean demoModeEnabled;
    public TransportBusinessService(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, false);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TransportBusinessService(JdbcTemplate jdbc, ObjectMapper json,
                                    @Value("${app.demo-mode.enabled:false}") boolean demoModeEnabled) {
        this.jdbc = jdbc;
        this.json = json;
        this.demoModeEnabled = demoModeEnabled;
    }
    public boolean demoModeEnabled() { return demoModeEnabled; }
    private static final String TASK_SELECT = """
            SELECT t.*, r.origin_city, r.destination_city, r.route_name,
                   u.display_name AS driver_name, v.plate_number,
                   f.actual_distance_km, f.actual_fuel_liters, f.comment
            FROM transport_tasks t JOIN routes r ON r.id=t.route_id
            LEFT JOIN users u ON u.id=t.driver_id LEFT JOIN vehicles v ON v.id=t.vehicle_id
            LEFT JOIN driver_feedback f ON f.task_id=t.id
            """;
    private static final RowMapper<TaskView> TASK_MAPPER = (rs, n) -> new TaskView(
            rs.getLong("id"), rs.getString("task_code"), rs.getLong("route_id"), rs.getString("origin_city"),
            rs.getString("destination_city"), rs.getString("route_name"), rs.getString("cargo_description"),
            rs.getObject("cargo_weight_tons", Double.class), rs.getObject("planned_distance_km", Double.class),
            rs.getObject("planned_duration_minutes", Double.class), rs.getObject("price_per_km", Double.class),
            localDateTime(rs.getTimestamp("delivery_deadline")),
            rs.getString("task_details"), rs.getObject("driver_id", Long.class),
            rs.getString("driver_name"), rs.getObject("vehicle_id", Long.class), rs.getString("plate_number"),
            rs.getString("task_status"), instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")),
            rs.getString("source_type"), rs.getBoolean("demo_mode"), rs.getString("sample_status"), rs.getString("exclusion_reason"),
            rs.getObject("actual_distance_km", Double.class), rs.getObject("actual_fuel_liters", Double.class),
            rs.getString("comment"), instant(rs.getTimestamp("created_at")));

    public List<TaskView> tasks(UserPrincipal actor) {
        return actor.role() == UserRole.DRIVER
                ? jdbc.query(TASK_SELECT + " WHERE t.driver_id=? ORDER BY t.created_at DESC,t.id DESC", TASK_MAPPER, actor.id())
                : jdbc.query(TASK_SELECT + " ORDER BY t.created_at DESC,t.id DESC", TASK_MAPPER);
    }
    public TaskView task(long id, UserPrincipal actor) {
        var results = jdbc.query(TASK_SELECT + " WHERE t.id=?", TASK_MAPPER, id);
        if (results.isEmpty()) throw new IllegalArgumentException("任务不存在。");
        TaskView task = results.getFirst();
        if (actor.role() == UserRole.DRIVER && !Long.valueOf(actor.id()).equals(task.driverId())) throw new ForbiddenException("只能访问分配给自己的运输任务。");
        return task;
    }
    public TaskView taskByCode(String code, UserPrincipal actor) {
        var ids = jdbc.queryForList("SELECT id FROM transport_tasks WHERE task_code=?", Long.class, code);
        if (ids.isEmpty()) throw new IllegalArgumentException("任务不存在。");
        return task(ids.getFirst(), actor);
    }
    public List<VehicleView> vehicles(UserPrincipal actor) {
        String driverFilter = actor.role() == UserRole.DRIVER ? " WHERE v.driver_id=? OR EXISTS(SELECT 1 FROM transport_tasks own WHERE own.vehicle_id=v.id AND own.driver_id=?)" : "";
        return jdbc.query("""
                SELECT v.*,
                    du.display_name AS bound_driver_name,
                    CASE WHEN v.vehicle_status='AVAILABLE' AND EXISTS(
                        SELECT 1 FROM transport_tasks t WHERE t.vehicle_id=v.id AND t.task_status IN ('PENDING_ACCEPTANCE','IN_TRANSIT'))
                    THEN 'RESERVED' ELSE v.vehicle_status END AS effective_status
                FROM vehicles v LEFT JOIN users du ON du.id=v.driver_id
                """ + driverFilter + " ORDER BY v.id", (rs,n) -> new VehicleView(rs.getLong("id"),
                rs.getString("plate_number"), rs.getString("vehicle_type"), rs.getString("energy_type"),
                rs.getObject("total_weight_tons", Double.class), rs.getObject("max_load_tons", Double.class),
                rs.getObject("length_m", Double.class), rs.getObject("width_m", Double.class),
                rs.getObject("height_m", Double.class), rs.getObject("axle_count", Integer.class),
                rs.getObject("has_etc", Boolean.class), rs.getObject("fuel_model_liters_per_100km", Double.class),
                rs.getObject("driver_id", Long.class), rs.getString("bound_driver_name"), rs.getString("effective_status"),
                rs.getDate("next_service_on") == null ? null : rs.getDate("next_service_on").toLocalDate()),
                actor.role() == UserRole.DRIVER ? new Object[]{actor.id(), actor.id()} : new Object[]{});
    }
    public List<DriverOption> drivers() {
        return jdbc.query("""
                SELECT u.id,u.username,u.display_name,u.phone,
                    EXISTS(SELECT 1 FROM transport_tasks t WHERE t.driver_id=u.id AND t.task_status IN ('PENDING_ACCEPTANCE','IN_TRANSIT')) AS busy
                FROM users u WHERE u.role='DRIVER' AND u.account_status='APPROVED' ORDER BY u.id
                """, (rs,n) -> new DriverOption(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"), rs.getString("phone"), rs.getBoolean("busy")));
    }
    public List<RouteOption> routes() {
        return jdbc.query("SELECT id,route_name,origin_city,destination_city,route_status,version_number,data_source FROM routes ORDER BY id DESC",
                (rs,n) -> new RouteOption(rs.getLong("id"), rs.getString("route_name"), rs.getString("origin_city"),
                        rs.getString("destination_city"), rs.getString("route_status"), rs.getInt("version_number"), rs.getString("data_source")));
    }
    @Transactional
    public TaskView create(long routeId, UserPrincipal actor) {
        return create(routeId, "历史任务", 1.0, 1.0, LocalDateTime.now().plusDays(365), "历史兼容任务，由系统补充默认信息。", actor);
    }
    @Transactional
    public TaskView create(long routeId, String cargoDescription, Double cargoWeightTons,
                           LocalDateTime deliveryDeadline, String details, UserPrincipal actor) {
        return create(routeId, cargoDescription, cargoWeightTons, 1.0, deliveryDeadline, details, actor);
    }
    @Transactional
    public TaskView create(long routeId, String cargoDescription, Double cargoWeightTons, Double pricePerKm,
                           LocalDateTime deliveryDeadline, String details, UserPrincipal actor) {
        requireDispatcher(actor);
        if (jdbc.queryForList("SELECT id FROM routes WHERE id=? FOR UPDATE", Long.class, routeId).isEmpty()) throw new IllegalArgumentException("基础路线不存在。");
        String cargo = required(cargoDescription, 120, "运送货物");
        positive(cargoWeightTons, 1000, "货物重量");
        positive(pricePerKm, 10000, "每公里单价");
        if (pricePerKm < 0.01 || java.math.BigDecimal.valueOf(pricePerKm).stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("每公里单价须至少为 0.01 元且最多保留两位小数。");
        if (deliveryDeadline == null || !deliveryDeadline.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("计划送达时间必须晚于当前时间。");
        String taskDetails = required(details, 1000, "任务详情");
        Double plannedDistanceKm = jdbc.queryForObject("SELECT COALESCE(SUM(distance_km),0) FROM route_segments WHERE route_id=?", Double.class, routeId);
        Double plannedDurationMinutes = jdbc.queryForObject("SELECT COALESCE(SUM(estimated_minutes),0) FROM route_segments WHERE route_id=?", Double.class, routeId);
        positive(plannedDistanceKm, 1e7, "路线预计距离");
        positive(plannedDurationMinutes, 1e7, "路线预计耗时");
        if (demoModeEnabled) requireDemoRoute(routeId);
        String code = "TASK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        long id = insert("""
                INSERT INTO transport_tasks(task_code,route_id,cargo_description,cargo_weight_tons,planned_distance_km,
                    planned_duration_minutes,price_per_km,delivery_deadline,task_details,task_status,source_type,demo_mode,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,'PENDING_ASSIGNMENT','SIMULATED',?,?)
                """, code, routeId, cargo, cargoWeightTons, plannedDistanceKm, plannedDurationMinutes,
                pricePerKm, Timestamp.valueOf(deliveryDeadline), taskDetails, demoModeEnabled, actor.id());
        audit(actor.id(), "TASK_CREATED", code, Map.of("routeId", routeId, "cargoDescription", cargo,
                "cargoWeightTons", cargoWeightTons, "plannedDistanceKm", plannedDistanceKm,
                "plannedDurationMinutes", plannedDurationMinutes, "pricePerKm", pricePerKm,
                "deliveryDeadline", deliveryDeadline.toString(),
                "demoMode", demoModeEnabled));
        return task(id, actor);
    }
    @Transactional
    public TaskView updatePendingTaskDetails(long id, TaskDetails request, UserPrincipal actor) {
        requireDispatcher(actor);
        var current = lockTask(id, actor);
        requireStatus(current, "PENDING_ASSIGNMENT");
        if (request == null) throw new IllegalArgumentException("任务资料不能为空。");
        String cargo = required(request.cargoDescription(), 120, "运送货物");
        positive(request.cargoWeightTons(), 1000, "货物重量");
        positive(request.pricePerKm(), 10000, "每公里单价");
        if (request.pricePerKm() < 0.01 || java.math.BigDecimal.valueOf(request.pricePerKm()).stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("每公里单价须至少为 0.01 元且最多保留两位小数。");
        if (request.deliveryDeadline() == null || !request.deliveryDeadline().isAfter(LocalDateTime.now()))
            throw new IllegalArgumentException("计划送达时间必须晚于当前时间。");
        String details = required(request.details(), 1000, "任务详情");
        Double distance = jdbc.queryForObject("SELECT COALESCE(SUM(distance_km),0) FROM route_segments WHERE route_id=?", Double.class, current.routeId());
        Double duration = jdbc.queryForObject("SELECT COALESCE(SUM(estimated_minutes),0) FROM route_segments WHERE route_id=?", Double.class, current.routeId());
        positive(distance, 1e7, "路线预计距离");
        positive(duration, 1e7, "路线预计耗时");
        jdbc.update("""
                UPDATE transport_tasks SET cargo_description=?,cargo_weight_tons=?,planned_distance_km=?,
                    planned_duration_minutes=?,price_per_km=?,delivery_deadline=?,task_details=?
                WHERE id=? AND task_status='PENDING_ASSIGNMENT'
                """, cargo, request.cargoWeightTons(), distance, duration, request.pricePerKm(),
                Timestamp.valueOf(request.deliveryDeadline()), details, id);
        audit(actor.id(), "TASK_DETAILS_UPDATED", current.code(), Map.of(
                "cargoWeightTons", request.cargoWeightTons(), "plannedDistanceKm", distance,
                "plannedDurationMinutes", duration, "pricePerKm", request.pricePerKm(),
                "deliveryDeadline", request.deliveryDeadline().toString()));
        return task(id, actor);
    }
    @Transactional
    public TaskView addHistoricalRate(long id, HistoricalRate request, UserPrincipal actor) {
        if (actor.role() != UserRole.DISPATCHER && actor.role() != UserRole.ADMIN && actor.role() != UserRole.SUPER_ADMIN)
            throw new ForbiddenException("当前账号不能补录历史任务单价。");
        var current = lockTask(id, actor);
        requireStatus(current, "COMPLETED");
        if (current.pricePerKm() != null) throw new IllegalStateException("该任务已有单价，不能通过补录覆盖。");
        if (request == null) throw new IllegalArgumentException("历史单价资料不能为空。");
        positive(request.pricePerKm(), 10000, "每公里单价");
        if (request.pricePerKm() < 0.01 || java.math.BigDecimal.valueOf(request.pricePerKm()).stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("每公里单价须至少为 0.01 元且最多保留两位小数。");
        String reference = required(request.sourceReference(), 512, "单价来源凭据");
        int updated = jdbc.update("UPDATE transport_tasks SET price_per_km=? WHERE id=? AND task_status='COMPLETED' AND price_per_km IS NULL", request.pricePerKm(), id);
        if (updated != 1) throw new IllegalStateException("历史任务状态已变化或单价已补录，请刷新后重试。");
        audit(actor.id(), "TASK_RATE_BACKFILLED", current.code(), Map.of("pricePerKm", request.pricePerKm(), "sourceReference", reference));
        return task(id, actor);
    }
    @Transactional
    public TaskView assign(long id, long driverId, long vehicleId, UserPrincipal actor) {
        requireDispatcher(actor);
        var task = lockTask(id, actor); requireStatus(task, "PENDING_ASSIGNMENT");
        requireTaskDetails(task);
        lockResources(driverId, vehicleId);
        var driver = jdbc.queryForList("SELECT role,account_status FROM users WHERE id=?", driverId).getFirst();
        if (!"DRIVER".equals(driver.get("role")) || !"APPROVED".equals(driver.get("account_status"))) throw new IllegalArgumentException("只能分配审核通过的司机。");
        String status = jdbc.queryForObject("SELECT vehicle_status FROM vehicles WHERE id=?", String.class, vehicleId);
        if (!"AVAILABLE".equals(status)) throw new IllegalStateException("车辆当前不可用。");
        Double capacity = jdbc.queryForObject("SELECT max_load_tons FROM vehicles WHERE id=?", Double.class, vehicleId);
        if (task.cargoWeightTons() != null && capacity < task.cargoWeightTons()) throw new IllegalStateException("该车辆额定载重不足，无法承运此任务货物。");
        Long boundDriver = jdbc.queryForObject("SELECT driver_id FROM vehicles WHERE id=?", Long.class, vehicleId);
        if (boundDriver != null && boundDriver != driverId) throw new IllegalStateException("该车辆已绑定其他司机，不能交叉分配。");
        if (activeTasks("driver_id", driverId) || activeTasks("vehicle_id", vehicleId)) throw new IllegalStateException("司机或车辆已有待接单/运输中任务。");
        requireSimulationRoute(task.routeId(), task.demoMode());
        jdbc.update("UPDATE transport_tasks SET driver_id=?,vehicle_id=?,task_status='PENDING_ACCEPTANCE' WHERE id=?", driverId,vehicleId,id);
        audit(actor.id(), "TASK_ASSIGNED", task.code(), Map.of("driverId",driverId,"vehicleId",vehicleId));
        return task(id,actor);
    }
    @Transactional
    public TaskView accept(long id, UserPrincipal actor) {
        requireDriver(actor);
        var task = lockTask(id,actor); requireStatus(task,"PENDING_ACCEPTANCE");
        lockResources(task.driverId(),task.vehicleId());
        requireSimulationRoute(task.routeId(), task.demoMode());
        jdbc.update("UPDATE transport_tasks SET task_status='IN_TRANSIT',started_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);
        jdbc.update("UPDATE vehicles SET vehicle_status='IN_TRANSIT' WHERE id=?",task.vehicleId());
        audit(actor.id(),"TASK_ACCEPTED",task.code(),Map.of());
        return task(id,actor);
    }
    @Transactional
    public void reportIssue(long id, TaskIssue request, UserPrincipal actor) {
        requireDriver(actor);
        var task = lockTask(id, actor);
        if (!List.of("PENDING_ACCEPTANCE", "IN_TRANSIT").contains(task.status()))
            throw new IllegalStateException("只有待接单或运输中的任务可以提交问题反馈。");
        if (request == null) throw new IllegalArgumentException("问题反馈不能为空。");
        String type = required(request.type(), 32, "反馈类型");
        if (!List.of("TIME_EXTENSION", "ACCIDENT", "EXTREME_WEATHER", "OTHER").contains(type))
            throw new IllegalArgumentException("反馈类型无效。");
        String message = required(request.message(), 500, "反馈内容");
        Map<String,Object> details = new java.util.LinkedHashMap<>();
        details.put("type", type);
        details.put("message", message);
        if (request.requestedDeadline() != null) details.put("requestedDeadline", request.requestedDeadline().toString());
        audit(actor.id(), "TASK_ISSUE_FEEDBACK", task.code(), details);
    }
    @Transactional
    public TaskView complete(long id, Completion request, UserPrincipal actor) {
        requireDriver(actor);
        var task=lockTask(id,actor); requireStatus(task,"IN_TRANSIT");
        lockResources(task.driverId(),task.vehicleId());
        positive(request.actualDistanceKm(),1e7,"实际里程"); positive(request.actualFuelLiters(),1e7,"实际油耗");
        String comment=optional(request.comment(),300,"运输反馈");
        // Route recoverability comes only from server-side matching, never from the feedback form.
        Instant completedAt=Instant.now();
        String reason=FeedbackPlausibility.exclusionReason(task.startedAt(),completedAt,request.actualDistanceKm(),request.actualFuelLiters());
        String sampleStatus=reason==null?"PENDING_MATCH":"INVALID";
        jdbc.update("INSERT INTO driver_feedback(task_id,actual_distance_km,actual_fuel_liters,comment) VALUES(?,?,?,?)",id,request.actualDistanceKm(),request.actualFuelLiters(),comment);
        jdbc.update("UPDATE transport_tasks SET task_status='COMPLETED',completed_at=?,sample_status=?,exclusion_reason=?,route_difference_recoverable=FALSE WHERE id=?",Timestamp.from(completedAt),sampleStatus,reason,id);
        jdbc.update("UPDATE vehicles SET vehicle_status='AVAILABLE' WHERE id=?",task.vehicleId());
        audit(actor.id(),"TASK_COMPLETED",task.code(),Map.of("sampleStatus",sampleStatus));
        return task(id,actor);
    }
    @Transactional
    public TaskView cancel(long id, UserPrincipal actor) {
        requireDispatcher(actor);
        var task=lockTask(id,actor);
        if (!List.of("PENDING_ASSIGNMENT","PENDING_ACCEPTANCE").contains(task.status())) throw new IllegalStateException("只能取消尚未开始运输的任务。");
        if(task.driverId()!=null) lockResources(task.driverId(),task.vehicleId());
        jdbc.update("UPDATE transport_tasks SET task_status='CANCELLED' WHERE id=?",id);
        audit(actor.id(),"TASK_CANCELLED",task.code(),Map.of());
        return task(id,actor);
    }
    @Transactional
    public long createVehicle(VehicleInput request,UserPrincipal actor) {
        requireAdmin(actor);
        String plate=required(request.plateNumber(),24,"车牌号"), type=required(request.vehicleType(),48,"车型");
        String energy=required(request.energyType(),24,"能源类型");
        validateVehicle(request);
        long driverId = approvedDriver(request.driverId());
        if(!jdbc.queryForList("SELECT id FROM vehicles WHERE plate_number=?",Long.class,plate).isEmpty()) throw new IllegalArgumentException("车牌号已存在。");
        long id=insert("""
                INSERT INTO vehicles(plate_number,vehicle_type,energy_type,total_weight_tons,max_load_tons,
                length_m,width_m,height_m,axle_count,has_etc,fuel_model_liters_per_100km,driver_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",plate,type,energy,request.totalWeightTons(),request.maxLoadTons(),
                request.lengthMeters(),request.widthMeters(),request.heightMeters(),request.axleCount(),request.hasEtc(),
                request.fuelModelLitersPer100km(),driverId);
        audit(actor.id(),"VEHICLE_CREATED",Long.toString(id),Map.of("plate",plate,"driverId",driverId));return id;
    }
    @Transactional
    public void updateVehicle(long id,VehicleInput request,UserPrincipal actor) {
        requireAdmin(actor);
        validateVehicle(request);
        if (jdbc.queryForList("SELECT id FROM vehicles WHERE id=? FOR UPDATE",Long.class,id).isEmpty()) throw new IllegalArgumentException("车辆不存在。");
        if (activeTasks("vehicle_id", id)) throw new IllegalStateException("车辆已有待接单或运输中任务，暂不能修改档案。");
        String plate=required(request.plateNumber(),24,"车牌号");
        if(!jdbc.queryForList("SELECT id FROM vehicles WHERE plate_number=? AND id<>?",Long.class,plate,id).isEmpty()) throw new IllegalArgumentException("车牌号已存在。");
        long driverId=approvedDriver(request.driverId());
        jdbc.update("""
                UPDATE vehicles SET plate_number=?,vehicle_type=?,energy_type=?,total_weight_tons=?,max_load_tons=?,
                length_m=?,width_m=?,height_m=?,axle_count=?,has_etc=?,fuel_model_liters_per_100km=?,driver_id=? WHERE id=?
                """,plate,required(request.vehicleType(),48,"车型"),required(request.energyType(),24,"能源类型"),request.totalWeightTons(),
                request.maxLoadTons(),request.lengthMeters(),request.widthMeters(),request.heightMeters(),request.axleCount(),request.hasEtc(),
                request.fuelModelLitersPer100km(),driverId,id);
        audit(actor.id(),"VEHICLE_PROFILE_CHANGED",Long.toString(id),Map.of("plate",plate,"driverId",driverId));
    }
    private void validateVehicle(VehicleInput request) {
        positive(request.totalWeightTons(),1000,"总质量"); positive(request.maxLoadTons(),1000,"核定载重");
        positive(request.lengthMeters(),100,"车长"); positive(request.widthMeters(),20,"车宽"); positive(request.heightMeters(),20,"车高");
        integerPositive(request.axleCount(),20,"轴数");
        if (request.hasEtc() == null) throw new IllegalArgumentException("ETC状态不能为空。");
        positive(request.fuelModelLitersPer100km(),200,"百公里油耗");
    }
    private long approvedDriver(Long driverId) {
        if (driverId == null) throw new IllegalArgumentException("必须绑定一名审核通过的司机。");
        var rows = jdbc.queryForList("SELECT role,account_status FROM users WHERE id=?", driverId);
        if (rows.isEmpty() || !"DRIVER".equals(rows.getFirst().get("role")) || !"APPROVED".equals(rows.getFirst().get("account_status")))
            throw new IllegalArgumentException("只能绑定审核通过的司机。");
        return driverId;
    }
    @Transactional
    public void vehicleStatus(long id,String status,UserPrincipal actor) {
        requireAdmin(actor);
        if(!List.of("AVAILABLE","MAINTENANCE").contains(status)) throw new IllegalArgumentException("请选择空闲或维修中；运输状态由任务控制。");
        if(jdbc.queryForList("SELECT id FROM vehicles WHERE id=? FOR UPDATE",Long.class,id).isEmpty()) throw new IllegalArgumentException("车辆不存在。");
        if(activeTasks("vehicle_id",id)) throw new IllegalStateException("车辆已有任务，不能修改维护状态。");
        jdbc.update("UPDATE vehicles SET vehicle_status=? WHERE id=?",status,id);
        audit(actor.id(),"VEHICLE_STATUS_CHANGED",Long.toString(id),Map.of("status",status));
    }
    @Transactional
    public void maintenanceReminder(long id,LocalDate nextServiceOn,UserPrincipal actor) {
        requireAdmin(actor);
        if(jdbc.queryForList("SELECT id FROM vehicles WHERE id=? FOR UPDATE",Long.class,id).isEmpty()) throw new IllegalArgumentException("车辆不存在。");
        jdbc.update("UPDATE vehicles SET next_service_on=? WHERE id=?",nextServiceOn,id);
        Map<String,Object> details=new java.util.HashMap<>();
        details.put("nextServiceOn",nextServiceOn==null?null:nextServiceOn.toString());
        audit(actor.id(),"VEHICLE_MAINTENANCE_REMINDER_CHANGED",Long.toString(id),details);
    }
    public List<Maintenance> maintenance(UserPrincipal actor) {
        requireAdmin(actor);
        return jdbc.query("SELECT m.*,v.plate_number,u.display_name FROM maintenance_records m JOIN vehicles v ON v.id=m.vehicle_id JOIN users u ON u.id=m.recorded_by ORDER BY m.serviced_on DESC,m.id DESC",
                (rs,n)->new Maintenance(rs.getLong("id"),rs.getLong("vehicle_id"),rs.getString("plate_number"),rs.getString("description"),rs.getDate("serviced_on").toLocalDate(),rs.getDate("next_service_on")==null?null:rs.getDate("next_service_on").toLocalDate(),rs.getString("display_name")));
    }
    @Transactional
    public long recordMaintenance(MaintenanceInput request,UserPrincipal actor) {
        requireAdmin(actor);
        String description=required(request.description(),900,"保养内容");
        if(request.servicedOn()==null || request.servicedOn().isAfter(LocalDate.now())) throw new IllegalArgumentException("保养日期不能为空或晚于今天。");
        int intervalDays = maintenanceInterval(request.intervalDays());
        LocalDate suggestedNextServiceOn = request.servicedOn().plusDays(intervalDays);
        LocalDate confirmedNextServiceOn = request.nextServiceOn()==null ? suggestedNextServiceOn : request.nextServiceOn();
        if(!confirmedNextServiceOn.isAfter(request.servicedOn())) throw new IllegalArgumentException("下次保养日期应晚于本次保养。");
        if(jdbc.queryForList("SELECT id FROM vehicles WHERE id=? FOR UPDATE",Long.class,request.vehicleId()).isEmpty()) throw new IllegalArgumentException("车辆不存在。");
        if(activeTasks("vehicle_id",request.vehicleId())) throw new IllegalStateException("车辆当前有运输任务，完成任务后才能登记保养。");
        long id=insert("INSERT INTO maintenance_records(vehicle_id,recorded_by,description,serviced_on,next_service_on) VALUES(?,?,?,?,?)",request.vehicleId(),actor.id(),description,request.servicedOn(),confirmedNextServiceOn);
        jdbc.update("UPDATE vehicles SET next_service_on=?,vehicle_status='AVAILABLE' WHERE id=?",confirmedNextServiceOn,request.vehicleId());
        Map<String,Object> details=new java.util.HashMap<>();
        details.put("recordId",id);
        details.put("intervalDays",intervalDays);
        details.put("suggestedNextServiceOn",suggestedNextServiceOn.toString());
        details.put("confirmedNextServiceOn",confirmedNextServiceOn.toString());
        audit(actor.id(),"MAINTENANCE_RECORDED",Long.toString(request.vehicleId()),details);return id;
    }
    public Profile profile(UserPrincipal actor) {
        return jdbc.query("SELECT username,display_name,phone,role FROM users WHERE id=?",(rs,n)->new Profile(rs.getString("username"),rs.getString("display_name"),rs.getString("phone"),rs.getString("role")),actor.id()).getFirst();
    }
    @Transactional
    public void updateProfile(ProfileInput request,UserPrincipal actor) {
        String name=required(request.displayName(),80,"姓名"),phone=optional(request.phone(),32,"联系方式");
        jdbc.update("UPDATE users SET display_name=?,phone=? WHERE id=?",name,phone,actor.id());
        audit(actor.id(),"PROFILE_UPDATED",Long.toString(actor.id()),Map.of());
    }

    private TaskView lockTask(long id,UserPrincipal actor) {
        if(jdbc.queryForList("SELECT id FROM transport_tasks WHERE id=? FOR UPDATE",Long.class,id).isEmpty()) throw new IllegalArgumentException("任务不存在。");
        return task(id,actor);
    }
    private void lockResources(long driverId,long vehicleId) {
        if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,driverId).isEmpty()) throw new IllegalArgumentException("司机不存在。");
        if(jdbc.queryForList("SELECT id FROM vehicles WHERE id=? FOR UPDATE",Long.class,vehicleId).isEmpty()) throw new IllegalArgumentException("车辆不存在。");
    }
    private boolean activeTasks(String column,long id) {
        // Column comes only from constants at call sites.
        return !jdbc.queryForList("SELECT id FROM transport_tasks WHERE "+column+"=? AND task_status IN ('PENDING_ACCEPTANCE','IN_TRANSIT') FOR UPDATE",Long.class,id).isEmpty();
    }
    public void requireVerifiedRoute(long routeId) {
        validateRoute(routeId, false);
    }

    public void requireDemoRoute(long routeId) {
        if (!demoModeEnabled) throw new IllegalStateException("演示模式未启用，不能使用待核验路线。");
        validateRoute(routeId, true);
    }

    public void requireSimulationRoute(long routeId, boolean demoTask) {
        if (demoTask) {
            requireDemoRoute(routeId);
            return;
        }
        requireVerifiedRoute(routeId);
    }

    private void validateRoute(long routeId, boolean allowPending) {
        var route=jdbc.queryForList("SELECT origin_city,destination_city,route_status FROM routes WHERE id=? FOR UPDATE",routeId);
        if(route.isEmpty()) throw new IllegalArgumentException("路线不存在。");
        Object routeStatus=route.getFirst().get("route_status");
        boolean pendingRoute=allowPending && "PENDING_VERIFICATION".equals(routeStatus);
        if(!"VERIFIED".equals(routeStatus) && !pendingRoute) throw new IllegalStateException("路线尚未完成逐段货车核验，只能保存待分配任务。");
        var segments=jdbc.queryForList("SELECT * FROM route_segments WHERE route_id=? ORDER BY sequence_number FOR UPDATE",routeId);
        if(segments.isEmpty()) throw new IllegalStateException("路线缺少道路段。");
        String previous=route.getFirst().get("origin_city").toString();int sequence=1;
        RoadGeometry.Geometry previousGeometry=null;
        for(var segment:segments) {
            boolean segmentVerified="VERIFIED".equals(segment.get("truck_access_status"));
            boolean segmentPending=allowPending && "PENDING".equals(segment.get("truck_access_status"));
            if(((Number)segment.get("sequence_number")).intValue()!=sequence++ || !previous.equals(segment.get("from_city")) || (!segmentVerified && !segmentPending)
                    || (segmentVerified && (segment.get("verified_at")==null || blank(segment.get("truck_evidence_source")) || blank(segment.get("truck_evidence_reference")))) || blank(segment.get("geometry_json"))
                    || blank(segment.get("map_source")) || blank(segment.get("map_response_reference"))) throw new IllegalStateException("路线存在未核验、不连续或缺少道路几何的路段。");
            var geometry=RoadGeometry.parse(segment.get("geometry_json").toString(),json);
            if(previousGeometry!=null && (!previousGeometry.coordinateSystem().equals(geometry.coordinateSystem())
                    || RoadGeometry.distanceKm(previousGeometry.points().getLast(),geometry.points().getFirst())>0.3)) throw new IllegalStateException("相邻道路段几何不连续或坐标系不一致。");
            previousGeometry=geometry;
            if(((Number)segment.get("distance_km")).doubleValue()<=0 || ((Number)segment.get("estimated_minutes")).doubleValue()<=0) throw new IllegalStateException("道路段指标无效。");
            previous=segment.get("to_city").toString();
        }
        if(!previous.equals(route.getFirst().get("destination_city"))) throw new IllegalStateException("道路段未到达路线终点。");
    }
    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);return statement;},key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }
    public void audit(long actor,String action,String entityId,Map<String,?> details) {
        try { jdbc.update("INSERT INTO audit_logs(actor_user_id,action,entity_type,entity_id,details_json) VALUES(?,?,'BUSINESS',?,?)",actor,action,entityId,json.writeValueAsString(details)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("无法记录操作详情。",e); }
    }
    private static void requireStatus(TaskView task,String status) { if(!status.equals(task.status())) throw new IllegalStateException("当前任务状态不允许该操作，请刷新。"); }
    private static void requireTaskDetails(TaskView task) {
        if (blank(task.cargoDescription()) || task.cargoWeightTons() == null || task.pricePerKm() == null
                || task.plannedDistanceKm() == null || task.plannedDurationMinutes() == null
                || task.deliveryDeadline() == null || blank(task.details()))
            throw new IllegalStateException("任务资料不完整，请先补充货物、重量、单价、路线距离、时限和任务详情。");
    }
    private static void requireDispatcher(UserPrincipal actor) { if(actor.role()!=UserRole.DISPATCHER) throw new ForbiddenException("只有调度员可以创建、分配或取消任务。"); }
    private static void requireDriver(UserPrincipal actor) { if(actor.role()!=UserRole.DRIVER) throw new ForbiddenException("只有任务司机可以执行运输。"); }
    private static void requireAdmin(UserPrincipal actor) { if(actor.role()!=UserRole.SUPER_ADMIN && actor.role()!=UserRole.ADMIN) throw new ForbiddenException("只有管理员可以维护车辆。"); }
    private static boolean blank(Object value) { return value==null || value.toString().isBlank(); }
    public static String required(String value,int max,String field) { if(value==null || value.isBlank() || value.trim().length()>max) throw new IllegalArgumentException(field+"不能为空且不能超过 "+max+" 个字符。");return value.trim(); }
    public static String optional(String value,int max,String field) { if(value==null || value.isBlank())return null;return required(value,max,field); }
    public static void positive(Double value,double max,String field) { if(value==null || !Double.isFinite(value) || value<=0 || value>max)throw new IllegalArgumentException(field+"须为大于 0 且不超过 "+max+" 的有限数值。"); }
    private static void integerPositive(Integer value,int max,String field) { if(value==null || value<=0 || value>max)throw new IllegalArgumentException(field+"须为 1 到 "+max+" 的整数。"); }
    private static int maintenanceInterval(Integer value) {
        int interval=value==null ? DEFAULT_MAINTENANCE_INTERVAL_DAYS : value;
        if(interval<1 || interval>3650) throw new IllegalArgumentException("保养周期须为 1 到 3650 天。");
        return interval;
    }
    private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
    private static LocalDateTime localDateTime(Timestamp value) { return value==null?null:value.toLocalDateTime(); }
    public record TaskView(long id,String code,long routeId,String originCity,String destinationCity,String routeName,
                           String cargoDescription,Double cargoWeightTons,Double plannedDistanceKm,Double plannedDurationMinutes,
                           Double pricePerKm,LocalDateTime deliveryDeadline,String details,Long driverId,String driverName,Long vehicleId,
                           String plateNumber,String status,Instant startedAt,Instant completedAt,String sourceType,
                           boolean demoMode,String sampleStatus,String exclusionReason,Double actualDistanceKm,
                           Double actualFuelLiters,String comment,Instant createdAt) {}
    public record VehicleView(long id,String plateNumber,String vehicleType,String energyType,Double totalWeightTons,Double maxLoadTons,
                              Double lengthMeters,Double widthMeters,Double heightMeters,Integer axleCount,Boolean hasEtc,
                              Double fuelModelLitersPer100km,Long driverId,String driverName,String status,LocalDate nextServiceOn) {}
    public record DriverOption(long id,String username,String displayName,String phone,boolean busy) {}
    public record RouteOption(long id,String name,String originCity,String destinationCity,String status,int version,String source) {}
    public record Completion(Double actualDistanceKm,Double actualFuelLiters,String comment) {}
    public record TaskDetails(String cargoDescription,Double cargoWeightTons,Double pricePerKm,
                              LocalDateTime deliveryDeadline,String details) {}
    public record HistoricalRate(Double pricePerKm,String sourceReference) {}
    public record TaskIssue(String type,String message,LocalDateTime requestedDeadline) {}
    public record VehicleInput(String plateNumber,String vehicleType,String energyType,Double totalWeightTons,Double maxLoadTons,
                               Double lengthMeters,Double widthMeters,Double heightMeters,Integer axleCount,Boolean hasEtc,
                               Double fuelModelLitersPer100km,Long driverId) {
        public VehicleInput(String plateNumber,String vehicleType,Double maxLoadTons,Double fuelModelLitersPer100km) {
            this(plateNumber,vehicleType,"柴油",null,maxLoadTons,null,null,null,null,false,fuelModelLitersPer100km,null);
        }
    }
    public record Maintenance(long id,long vehicleId,String plateNumber,String description,LocalDate servicedOn,LocalDate nextServiceOn,String recordedBy) {}
    public record MaintenanceInput(long vehicleId,String description,LocalDate servicedOn,LocalDate nextServiceOn,Integer intervalDays) {
        public MaintenanceInput(long vehicleId,String description,LocalDate servicedOn,LocalDate nextServiceOn) {
            this(vehicleId,description,servicedOn,nextServiceOn,null);
        }
    }
    public record Profile(String username,String displayName,String phone,String role) {}
    public record ProfileInput(String displayName,String phone) {}
}
