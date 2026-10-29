package cn.logistics.platform.optimization;
import cn.logistics.platform.domain.RoadGeometry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RoadMatcherTest {
    final List<RoadGeometry.Point> line=List.of(new RoadGeometry.Point(110,30),new RoadGeometry.Point(110.1,30));
    RoadMatcher.Path path(long id,String signature){return new RoadMatcher.Path(id,signature,List.of(new RoadMatcher.Segment(id,line)));}
    List<RoadMatcher.Observation> observations(){var points=RoadGeometry.sample(line,1);var observations=new ArrayList<RoadMatcher.Observation>();for(int i=0;i<points.size();i++)observations.add(new RoadMatcher.Observation(i+1,points.get(i)));return observations;}
    @Test void geometryAmbiguityRejectsDifferentRoadsButDeduplicatesExactVersionCopies(){var points=observations();assertFalse(RoadMatcher.match(points,points.size(),List.of(path(1,"ROAD1"),path(2,"ROAD2")),.15).valid());assertTrue(RoadMatcher.match(points,points.size(),List.of(path(1,"ROAD1"),path(2,"ROAD1")),.15).valid());}
    @Test void allowsSmallInteriorGapButRejectsMissingEndpointAndReverseTravel(){var points=observations();int total=points.size();var gap=new ArrayList<>(points);gap.remove(4);assertTrue(RoadMatcher.match(gap,total,List.of(path(1,"ROAD1")),.15).valid());gap.remove(0);assertFalse(RoadMatcher.match(gap,total,List.of(path(1,"ROAD1")),.15).valid());var reverse=new ArrayList<RoadMatcher.Observation>();for(int i=0;i<points.size();i++)reverse.add(new RoadMatcher.Observation(i+1,points.get(points.size()-1-i).point()));assertFalse(RoadMatcher.match(reverse,total,List.of(path(1,"ROAD1")),.15).valid());}
}
