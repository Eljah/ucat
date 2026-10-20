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
    /** Bounding box [south, west, north, east] in EPSG:4326; Overpass uses this order. */
    public static double[] bounds(Map<String,Object> geometry) {
        validate(geometry);
        double[] b={90,180,-90,-180};
        collectPoints(geometry.get("coordinates"),b);
        return b;
    }
    private static void collectPoints(Object nested,double[] b) {
        List<Object> list=Json.array(nested);
        if(list.size()>=2&&list.get(0) instanceof Number&&list.get(1) instanceof Number) {
            double lon=num(list.get(0)),lat=num(list.get(1));
            b[0]=Math.min(b[0],lat);b[1]=Math.min(b[1],lon);
            b[2]=Math.max(b[2],lat);b[3]=Math.max(b[3],lon);
        } else for(Object child:list)collectPoints(child,b);
    }
    /** A line crossing the polygon is relevant even when neither endpoint is inside it. */
    public static boolean intersectsLine(Map<String,Object> area,List<Object> line) {
        if(line.isEmpty())return false;
        for(Object pt:line) {
            List<Object> p=Json.array(pt);
            if(contains(area,num(p.get(0)),num(p.get(1))))return true;
        }
        if(line.size()<2)return false;
        for(List<Object> ring:allRings(area)) {
            for(int i=1;i<line.size();i++) {
                List<Object> a=Json.array(line.get(i-1)),b=Json.array(line.get(i));
                for(int j=1;j<ring.size();j++) {
                    List<Object> c=Json.array(ring.get(j-1)),d=Json.array(ring.get(j));
                    if(segmentCrosses(a,b,c,d))return true;
                }
            }
        }
        return false;
    }
    /** Polygon against polygon, with holes. Suitable for imported cadastral footprints. */
    public static boolean intersectsPolygons(Map<String,Object> a,Map<String,Object> b) {
        validate(a);validate(b);
        double[] ba=bounds(a),bb=bounds(b);
        if(ba[2]<bb[0]||bb[2]<ba[0]||ba[3]<bb[1]||bb[3]<ba[1])return false;
        for(List<Object> ring:allRings(a))for(Object pt:ring) {
            List<Object> p=Json.array(pt);
            if(contains(b,num(p.get(0)),num(p.get(1))))return true;
        }
        for(List<Object> ring:allRings(b))for(Object pt:ring) {
            List<Object> p=Json.array(pt);
            if(contains(a,num(p.get(0)),num(p.get(1))))return true;
        }
        for(List<Object> ra:allRings(a))for(List<Object> rb:allRings(b))
            for(int i=1;i<ra.size();i++)for(int j=1;j<rb.size();j++)
                if(segmentCrosses(Json.array(ra.get(i-1)),Json.array(ra.get(i)),
                        Json.array(rb.get(j-1)),Json.array(rb.get(j))))return true;
        return false;
    }
    private static List<List<Object>> allRings(Map<String,Object> geometry) {
        List<List<Object>> rings=new ArrayList<>();
        List<Object> co=Json.array(geometry.get("coordinates"));
        if("Polygon".equals(geometry.get("type")))for(Object ring:co)rings.add(Json.array(ring));
        else for(Object poly:co)for(Object ring:Json.array(poly))rings.add(Json.array(ring));
        return rings;
    }
    private static boolean segmentCrosses(List<Object> a,List<Object> b,List<Object> c,List<Object> d) {
        double ax=num(a.get(0)),ay=num(a.get(1)),bx=num(b.get(0)),by=num(b.get(1));
        double cx=num(c.get(0)),cy=num(c.get(1)),dx=num(d.get(0)),dy=num(d.get(1));
        double o1=orientation(ax,ay,bx,by,cx,cy),o2=orientation(ax,ay,bx,by,dx,dy);
        double o3=orientation(cx,cy,dx,dy,ax,ay),o4=orientation(cx,cy,dx,dy,bx,by);
        final double eps=1e-11;
        if((o1>eps&&o2< -eps||o1< -eps&&o2>eps) &&
           (o3>eps&&o4< -eps||o3< -eps&&o4>eps))return true;
        return Math.abs(o1)<=eps&&pointOnSegment(cx,cy,ax,ay,bx,by)
            || Math.abs(o2)<=eps&&pointOnSegment(dx,dy,ax,ay,bx,by)
            || Math.abs(o3)<=eps&&pointOnSegment(ax,ay,cx,cy,dx,dy)
            || Math.abs(o4)<=eps&&pointOnSegment(bx,by,cx,cy,dx,dy);
    }
    private static double orientation(double ax,double ay,double bx,double by,double cx,double cy) {
        return (bx-ax)*(cy-ay)-(by-ay)*(cx-ax);
    }

}
