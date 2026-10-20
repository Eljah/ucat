package ru.activisthelp;

import java.util.*;

/** GeoJSON coordinates are [longitude, latitude] (WGS84). Supports Polygon and MultiPolygon with holes. */
public final class Geo {
    private Geo() {}
    public static boolean contains(Map<String,Object> geometry, double longitude, double latitude) {
        String type=Json.string(geometry.get("type"));
        List<Object> coordinates=Json.array(geometry.get("coordinates"));
        if (type.equals("Polygon")) return polygon(coordinates,longitude,latitude);
        if (type.equals("MultiPolygon")) {
            for(Object poly:coordinates) if(polygon(Json.array(poly),longitude,latitude))return true;
            return false;
        }
        throw new IllegalArgumentException("Only Polygon/MultiPolygon supported");
    }
    private static boolean polygon(List<Object> rings, double lon, double lat) {
        if(rings.isEmpty()|| !ring(Json.array(rings.get(0)),lon,lat))return false;
        for(int i=1;i<rings.size();i++)if(ring(Json.array(rings.get(i)),lon,lat))return false;
        return true;
    }
    private static boolean ring(List<Object> pts,double x,double y) {
        if(pts.size()<3)return false;
        boolean inside=false;
        for(int i=0,j=pts.size()-1;i<pts.size();j=i++) {
            List<Object> ai=Json.array(pts.get(i)), aj=Json.array(pts.get(j));
            double xi=num(ai.get(0)),yi=num(ai.get(1)),xj=num(aj.get(0)),yj=num(aj.get(1));
            if(pointOnSegment(x,y,xi,yi,xj,yj))return true;
            if((yi>y)!=(yj>y) && x<(xj-xi)*(y-yi)/(yj-yi)+xi)inside=!inside;
        }
        return inside;
    }
    private static boolean pointOnSegment(double x,double y,double a,double b,double c,double d) {
        double cross=(x-a)*(d-b)-(y-b)*(c-a);
        return Math.abs(cross)<1e-10 && x>=Math.min(a,c)-1e-10 && x<=Math.max(a,c)+1e-10
            && y>=Math.min(b,d)-1e-10 && y<=Math.max(b,d)+1e-10;
    }
    static double num(Object o) { if(!(o instanceof Number))throw new IllegalArgumentException("Expected coordinate number");return ((Number)o).doubleValue(); }
    public static void validate(Map<String,Object> geometry) {
        Object value=geometry.get("coordinates");
        String type=Json.string(geometry.get("type"));
        if(!type.equals("Polygon")&&!type.equals("MultiPolygon"))throw new IllegalArgumentException("Only Polygon or MultiPolygon");
        int[] points={0};validatePolygons(type.equals("Polygon")?List.of(value):Json.array(value), points);
        if(points[0]>3000)throw new IllegalArgumentException("Polygon is too large");
    }
    private static void validatePolygons(List<?> polygons,int[] count) {
        for(Object p:polygons) {
            List<Object> rings=Json.array(p);
            if(rings.isEmpty())throw new IllegalArgumentException("Polygon must have outer ring");
            for(Object r:rings) {
                List<Object> pts=Json.array(r);
                if(pts.size()<4)throw new IllegalArgumentException("Closed ring needs at least four coordinates");
                for(Object pt:pts) {
                    List<Object> pair=Json.array(pt);
                    if(pair.size()<2)throw new IllegalArgumentException("Invalid coordinate");
                    double lon=num(pair.get(0)),lat=num(pair.get(1));
                    if(!Double.isFinite(lon)||!Double.isFinite(lat)||lon<-180||lon>180||lat<-90||lat>90)throw new IllegalArgumentException("Invalid WGS84 location");
                    count[0]++;
                }
                if(!pts.get(0).equals(pts.get(pts.size()-1)))throw new IllegalArgumentException("Ring must be closed");
            }
        }
    }
}
