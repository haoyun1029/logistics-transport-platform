package cn.logistics.streaming;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;

/** Deviation against the server supplied plan geometry; this is not road matching. */
public final class VerifiedRoadDetector {
    private VerifiedRoadDetector(){}
    public static final class State implements java.io.Serializable {
        public String run;public int sequence;public int outsideCount;public long outsideSince;public long lastTimestamp;
        public State(){}
    }
    public static State apply(ObjectNode result,State previous){
        JsonNode raw=result.path("rawEvent"),context=raw.path("plannedRoadContext"),policy=context.path("policy");
        if(!"VALID".equals(result.path("validationStatus").asText()) || context.isMissingNode())return previous;
        State state=previous==null?new State():previous;
        try{
            String run=raw.path("simulationRunId").asText();int sequence=raw.path("sequence").asInt();
            if(run.isEmpty()||!"WGS84".equals(raw.path("coordinateSystem").asText())||!"WGS84".equals(context.path("coordinateSystem").asText())||!"SERVER_VERIFIED_ROUTE".equals(context.path("geometrySource").asText()))throw new IllegalArgumentException();
            JsonNode coords=context.path("coordinates");if(!coords.isArray()||coords.size()<2||coords.size()>20000)throw new IllegalArgumentException();
            double threshold=policy.path("deviationDistanceKm").asDouble(),speed=policy.path("speedLimitKmh").asDouble();int count=policy.path("deviationConsecutivePoints").asInt(),seconds=policy.path("deviationDurationSeconds").asInt();
            if(!Double.isFinite(threshold)||threshold<=0||threshold>5||!Double.isFinite(speed)||speed<10||speed>150||count<2||count>20||seconds<1||seconds>600)throw new IllegalArgumentException();
            double lon=raw.path("longitude").asDouble(),lat=raw.path("latitude").asDouble(),best=Double.POSITIVE_INFINITY,cos=Math.cos(Math.toRadians(lat));
            for(int i=1;i<coords.size();i++){
                JsonNode a=coords.get(i-1),b=coords.get(i);checkPair(a);checkPair(b);
                double ax=(a.get(0).asDouble()-lon)*111.195*cos,ay=(a.get(1).asDouble()-lat)*111.195,bx=(b.get(0).asDouble()-lon)*111.195*cos,by=(b.get(1).asDouble()-lat)*111.195;
                double dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy,t=den==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/den));best=Math.min(best,Math.hypot(ax+t*dx,ay+t*dy));
            }
            long timestamp=Instant.parse(result.path("eventTime").asText()).toEpochMilli();
            if(!run.equals(state.run)){state=new State();state.run=run;}
            ArrayNode anomalies=result.putArray("anomalies");if(raw.path("speedKmh").asDouble()>speed)anomalies.add("OVERSPEED");
            result.put("speedLimitKmh",speed);result.put("policyVersion",policy.path("version").asLong());result.put("plannedRouteDistanceKm",best);result.put("deviationEvidenceSource","SERVER_SUPPLIED_PLAN_GEOMETRY");
            if(sequence<=state.sequence){result.put("deviationStatus","NOT_EVALUATED_DUPLICATE_OR_OUT_OF_ORDER");return state;}
            if(sequence!=state.sequence+1||timestamp<=state.lastTimestamp){state.outsideCount=0;state.outsideSince=0;}
            if(best>threshold){if(state.outsideCount==0)state.outsideSince=timestamp;state.outsideCount++;boolean sustained=state.outsideCount>=count&&timestamp-state.outsideSince>=seconds*1000L;
                result.put("deviationStatus",sustained?"SUSTAINED_DEVIATION":"PENDING_CONTINUOUS_EVIDENCE");if(sustained)anomalies.add("SUSTAINED_DEVIATION");
            }else{state.outsideCount=0;state.outsideSince=0;result.put("deviationStatus","WITHIN_PLANNED_CORRIDOR");}
            state.sequence=sequence;state.lastTimestamp=timestamp;result.put("consecutiveOutsidePoints",state.outsideCount);
        }catch(RuntimeException e){result.put("deviationStatus","NOT_EVALUATED_INVALID_PLAN_CONTEXT");state=new State();}
        return state;
    }
    private static void checkPair(JsonNode pair){if(!pair.isArray()||pair.size()!=2||!pair.get(0).isNumber()||!pair.get(1).isNumber()||!Double.isFinite(pair.get(0).asDouble())||!Double.isFinite(pair.get(1).asDouble())||Math.abs(pair.get(0).asDouble())>180||Math.abs(pair.get(1).asDouble())>90)throw new IllegalArgumentException();}
}
