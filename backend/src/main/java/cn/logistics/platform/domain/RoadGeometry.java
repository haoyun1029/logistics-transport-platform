package cn.logistics.platform.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** Coordinates are stored explicitly; no inferred city-centre geometry is accepted here. */
public final class RoadGeometry {
    private RoadGeometry() {}
    public record Point(double longitude,double latitude) {}
    public record Geometry(String coordinateSystem,List<Point> points) {}
    public static Geometry parse(String value,ObjectMapper mapper) {
        try {
            var root=mapper.readTree(value);
            String system=root.path("coordinateSystem").asText();
            if(!List.of("WGS84","GCJ02").contains(system)) throw new IllegalArgumentException("道路几何须明确 WGS84 或 GCJ02 坐标系。");
            var coordinates=root.path("coordinates");
            if(!coordinates.isArray() || coordinates.size()<2 || coordinates.size()>20000) throw new IllegalArgumentException("每段道路须包含 2–20000 个有序坐标点。");
            List<Point> points=new ArrayList<>();
            for(var pair:coordinates) {
                if(!pair.isArray() || pair.size()!=2 || !pair.get(0).isNumber() || !pair.get(1).isNumber()) throw new IllegalArgumentException("道路坐标格式为 [经度,纬度]。");
                double lon=pair.get(0).doubleValue(),lat=pair.get(1).doubleValue();
                if(!Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon)>180 || Math.abs(lat)>90) throw new IllegalArgumentException("道路坐标越界。");
                points.add(new Point(lon,lat));
            }
            if(lengthKm(points)<0.01) throw new IllegalArgumentException("道路几何长度过短。");
            return new Geometry(system,List.copyOf(points));
        } catch(com.fasterxml.jackson.core.JsonProcessingException | NullPointerException e) { throw new IllegalArgumentException("道路几何 JSON 无效。",e); }
    }
    public static double distanceKm(Point a,Point b) {
        double lat=Math.toRadians(b.latitude()-a.latitude()),lon=Math.toRadians(b.longitude()-a.longitude());
        double h=Math.pow(Math.sin(lat/2),2)+Math.cos(Math.toRadians(a.latitude()))*Math.cos(Math.toRadians(b.latitude()))*Math.pow(Math.sin(lon/2),2);
        return 6371*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }
    public static double lengthKm(List<Point> points) { double total=0;for(int i=1;i<points.size();i++)total+=distanceKm(points.get(i-1),points.get(i));return total; }
    public static List<Point> wgs84(Geometry geometry) {
        if("WGS84".equals(geometry.coordinateSystem()))return geometry.points();
        return geometry.points().stream().map(p->{double x=p.longitude(),y=p.latitude();for(int i=0;i<4;i++){double[] d=gcjOffset(x,y);x-=x+d[0]-p.longitude();y-=y+d[1]-p.latitude();}return new Point(x,y);}).toList();
    }
    private static double[] gcjOffset(double lon,double lat){
        if(lon<72.004||lon>137.8347||lat<.8293||lat>55.8271)return new double[]{0,0};
        double x=lon-105,y=lat-35,pi=Math.PI;
        double a=-100+2*x+3*y+.2*y*y+.1*x*y+.2*Math.sqrt(Math.abs(x));
        double b=300+x+2*y+.1*x*x+.1*x*y+.1*Math.sqrt(Math.abs(x));
        double common=(20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3;
        a+=common+(20*Math.sin(y*pi)+40*Math.sin(y/3*pi))*2/3+(160*Math.sin(y/12*pi)+320*Math.sin(y*pi/30))*2/3;
        b+=common+(20*Math.sin(x*pi)+40*Math.sin(x/3*pi))*2/3+(150*Math.sin(x/12*pi)+300*Math.sin(x/30*pi))*2/3;
        double rad=lat/180*pi,magic=1-.006693421622965943*Math.pow(Math.sin(rad),2);
        return new double[]{b*180/(6378245/Math.sqrt(magic)*Math.cos(rad)*pi),a*180/((6378245*(1-.006693421622965943))/(magic*Math.sqrt(magic))*pi)};
    }
    /** Samples original polyline edges; never joins cities or skips bends. */
    public static List<Point> sample(List<Point> path,double intervalKm){
        var result=new ArrayList<Point>();result.add(path.getFirst());double until=intervalKm;
        for(int i=1;i<path.size();i++){
            var a=path.get(i-1);var b=path.get(i);double length=distanceKm(a,b),offset=until;
            while(offset<length){double f=offset/length;result.add(new Point(a.longitude()+(b.longitude()-a.longitude())*f,a.latitude()+(b.latitude()-a.latitude())*f));offset+=intervalKm;}
            until=offset-length;
        }
        if(distanceKm(result.getLast(),path.getLast())>.001)result.add(path.getLast());
        return List.copyOf(result);
    }
}
