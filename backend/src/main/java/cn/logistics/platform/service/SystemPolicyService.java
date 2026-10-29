package cn.logistics.platform.service;
import cn.logistics.platform.optimization.ScoringWeights;
import cn.logistics.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class SystemPolicyService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransportBusinessService business;
    public SystemPolicyService(JdbcTemplate jdbc,ObjectMapper json,TransportBusinessService business){this.jdbc=jdbc;this.json=json;this.business=business;}
    public record Policy(long version,double timeWeight,double distanceWeight,double fuelWeight,double speedLimitKmh,double matchingDistanceKm,double deviationDistanceKm,int deviationConsecutivePoints,int deviationDurationSeconds){
        public ScoringWeights weights(){return new ScoringWeights(timeWeight,distanceWeight,fuelWeight);}
        public static Policy defaults(){return new Policy(0,.4,.3,.3,80,.15,.5,3,30);}
        public void validate(){weights().validate();
            if(!Double.isFinite(speedLimitKmh)||speedLimitKmh<10||speedLimitKmh>150||!Double.isFinite(matchingDistanceKm)||matchingDistanceKm<.01||matchingDistanceKm>.3
                ||!Double.isFinite(deviationDistanceKm)||deviationDistanceKm<matchingDistanceKm||deviationDistanceKm>5||deviationConsecutivePoints<2||deviationConsecutivePoints>20||deviationDurationSeconds<1||deviationDurationSeconds>600)
                throw new IllegalArgumentException("阈值无效：速度 10–150 km/h，匹配 0.01–0.3 km，偏离距离不小于匹配距离且不超过 5 km，连续点 2–20、持续 1–600 秒。");
        }
    }
    public Policy current(){var rows=jdbc.queryForList("SELECT config_json FROM system_config WHERE id=1",String.class);return rows.isEmpty()?Policy.defaults():parse(rows.getFirst());}
    @Transactional public Policy save(Policy request,UserPrincipal actor){
        if(actor.role()!=UserRole.ADMIN&&actor.role()!=UserRole.SUPER_ADMIN)throw new ForbiddenException("仅管理员可维护业务参数。");
        request.validate();var rows=jdbc.queryForList("SELECT version_number FROM system_config WHERE id=1 FOR UPDATE",Long.class);
        long old=rows.isEmpty()?0:rows.getFirst();if(old!=request.version())throw new IllegalStateException("配置已更新，请刷新后重试。");
        var next=new Policy(old+1,request.timeWeight(),request.distanceWeight(),request.fuelWeight(),request.speedLimitKmh(),request.matchingDistanceKm(),request.deviationDistanceKm(),request.deviationConsecutivePoints(),request.deviationDurationSeconds());
        try {String value=json.writeValueAsString(next);if(rows.isEmpty())jdbc.update("INSERT INTO system_config(id,version_number,config_json,updated_by) VALUES(1,?,?,?)",next.version(),value,actor.id());else jdbc.update("UPDATE system_config SET version_number=?,config_json=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=1",next.version(),value,actor.id());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        business.audit(actor.id(),"SYSTEM_POLICY_UPDATED","1",Map.of("version",next.version()));return next;
    }
    private Policy parse(String value){try{return json.readValue(value,Policy.class);}catch(Exception e){throw new IllegalStateException("配置无法解析。",e);}}
}
