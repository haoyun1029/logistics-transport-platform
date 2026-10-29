package cn.logistics.streaming;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VerifiedRoadDetectorTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode event(int seq,int second,double latitude)throws Exception{
        return (ObjectNode)json.readTree("{\"validationStatus\":\"VALID\",\"eventTime\":\"2026-10-24T10:00:"+String.format("%02d",second)+"Z\",\"anomalies\":[],\"rawEvent\":{\"simulationRunId\":\"test\",\"sequence\":"+seq+",\"coordinateSystem\":\"WGS84\",\"latitude\":"+latitude+",\"longitude\":110.1,\"speedKmh\":85,\"plannedRoadContext\":{\"coordinateSystem\":\"WGS84\",\"geometrySource\":\"SERVER_VERIFIED_ROUTE\",\"coordinates\":[[110,30],[111,30]],\"policy\":{\"version\":4,\"speedLimitKmh\":90,\"deviationDistanceKm\":0.5,\"deviationConsecutivePoints\":3,\"deviationDurationSeconds\":20}}}}");
    }
    @Test void requiresBothConsecutivePointsAndElapsedTimeAndResetsOnReturn()throws Exception{
        ObjectNode a=event(1,0,30.1);VerifiedRoadDetector.State state=VerifiedRoadDetector.apply(a,null);assertEquals("PENDING_CONTINUOUS_EVIDENCE",a.path("deviationStatus").asText());assertTrue(a.path("anomalies").isEmpty());
        state=VerifiedRoadDetector.apply(event(2,10,30.1),state);ObjectNode c=event(3,30,30.1);state=VerifiedRoadDetector.apply(c,state);assertEquals("SUSTAINED_DEVIATION",c.path("deviationStatus").asText());assertEquals("SUSTAINED_DEVIATION",c.path("anomalies").get(0).asText());
        ObjectNode back=event(4,40,30);state=VerifiedRoadDetector.apply(back,state);assertEquals(0,state.outsideCount);assertEquals("WITHIN_PLANNED_CORRIDOR",back.path("deviationStatus").asText());
    }
    @Test void gapsDuplicatesAndBadContextDoNotManufactureContinuousEvidence()throws Exception{
        VerifiedRoadDetector.State state=VerifiedRoadDetector.apply(event(1,0,30.1),null);state=VerifiedRoadDetector.apply(event(3,10,30.1),state);assertEquals(1,state.outsideCount);
        ObjectNode duplicate=event(3,20,30.1);state=VerifiedRoadDetector.apply(duplicate,state);assertEquals("NOT_EVALUATED_DUPLICATE_OR_OUT_OF_ORDER",duplicate.path("deviationStatus").asText());assertEquals(1,state.outsideCount);
        ObjectNode invalid=event(4,30,30.1);((ObjectNode)invalid.path("rawEvent").path("plannedRoadContext")).put("coordinateSystem","UNKNOWN");VerifiedRoadDetector.apply(invalid,state);assertEquals("NOT_EVALUATED_INVALID_PLAN_CONTEXT",invalid.path("deviationStatus").asText());
    }
}

