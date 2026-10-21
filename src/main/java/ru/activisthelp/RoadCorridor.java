package ru.activisthelp;

import java.util.*;

/**
 * Routes one street between two named crossing streets using shared OSM node IDs.
 * We never infer an at-grade junction from two geometrically crossing lines: overpasses exist.
 * Even a successful route is an OSM approximation, not the official engineering alignment.
 */
public final class RoadCorridor {
    private RoadCorridor() {}
    private record Point(double lon,double lat) {}
    private record Edge(long id,double meters) {}
    private record State(long node,double distance) {}
    public static Map<String,Object> assess(LinearWork.Spec spec,Map<String,Object> area) {
        if(spec==null)return Map.of();
        if(!"CROSS_STREETS".equals(spec.type())||spec.mainRoad().isBlank())return unresolved(spec,"Нет основной улицы и двух поперечных улиц; нужен адресный/проектный план");
        List<Object> context=Json.array(area.getOrDefault("contextWays",List.of()));
        if(context.isEmpty())return unresolved(spec,"Геометрия окрестных дорог OSM не загружена");
        List<Map<String,Object>> main=select(context,spec.mainRoad());
        List<Map<String,Object>> from=select(context,spec.from().name());
        List<Map<String,Object>> to=select(context,spec.to().name());
        if(main.isEmpty()||from.isEmpty()||to.isEmpty())return unresolved(spec,"Не найдены все три улицы в загруженном фрагменте OSM; отсутствие не доказывает удалённость работ");
        Map<Long,Point> points=new HashMap<>();
        Map<Long,List<Edge>> graph=new HashMap<>();
        Set<Long> mainNodes=new HashSet<>();
        for(var road:main) {
            List<Object> ids=Json.array(road.getOrDefault("nodes",List.of()));
            List<Object> line=Json.array(road.getOrDefault("geometry",List.of()));
            if(ids.size()!=line.size())continue;
            long previous=-1;Point prev=null;
            for(int i=0;i<ids.size();i++) {
                if(!(ids.get(i) instanceof Number))continue;
                long node=((Number)ids.get(i)).longValue();
                List<Object> coords=Json.array(line.get(i));
                Point current=new Point(Geo.num(coords.get(0)),Geo.num(coords.get(1)));
                points.putIfAbsent(node,current);mainNodes.add(node);
                if(prev!=null&&previous!=node) {
                    double distance=distance(prev,current);
                    graph.computeIfAbsent(previous,k->new ArrayList<>()).add(new Edge(node,distance));
                    graph.computeIfAbsent(node,k->new ArrayList<>()).add(new Edge(previous,distance));
                }
                previous=node;prev=current;
            }
        }
        Set<Long> start=junctions(mainNodes,from),end=junctions(mainNodes,to);
        if(start.isEmpty()||end.isEmpty())return unresolved(spec,"Пересечения с поперечными улицами не подтверждены общими OSM-узлами");
        if(start.size()>8||end.size()>8)return unresolved(spec,"Слишком много возможных пересечений; требуется ручное уточнение");
        List<Long> route=shortest(graph,start,end);
        if(route.size()<2)return unresolved(spec,"Основная улица между пересечениями разорвана в OSM либо отсутствует в области загрузки");
        List<Object> line=new ArrayList<>();
        for(long node:route){Point p=points.get(node);if(p==null)return unresolved(spec,"Некорректная геометрия OSM");line.add(List.of(p.lon,p.lat));}
        boolean intersects=Geo.intersectsLine(Json.object(area.get("geometry")),line);
        double length=0;for(int i=1;i<route.size();i++)length+=distance(points.get(route.get(i-1)),points.get(route.get(i)));
        return Json.obj("status",intersects?"INTERSECTS_INFERRED":"OUTSIDE_INFERRED",
            "areaId",area.get("id"),"area",area.get("name"),"mainRoad",spec.mainRoad(),
            "from",spec.from().name(),"to",spec.to().name(),"lengthMeters",Math.round(length),
            "line",line,"method","OSM_SHARED_NODES_SHORTEST_PATH",
            "explanation",intersects?
                "Участок улицы между перекрёстками, восстановленный по узлам OSM, пересекает зону. Официальные границы работ ещё не подтверждены.":
                "Восстановленный по OSM участок не пересекает зону. Проверьте соответствие проектной документации и полноту дорожной сети.");
    }
    private static Map<String,Object> unresolved(LinearWork.Spec spec,String reason) {
        return Json.obj("status","UNRESOLVED","mainRoad",spec.mainRoad(),"from",spec.from()==null?"":spec.from().name(),
            "to",spec.to()==null?"":spec.to().name(),"explanation",reason);
    }
    private static Set<Long> junctions(Set<Long> main,List<Map<String,Object>> cross) {
        Set<Long> intersection=new LinkedHashSet<>();
        for(var road:cross)for(Object n:Json.array(road.getOrDefault("nodes",List.of())))
            if(n instanceof Number && main.contains(((Number)n).longValue()))intersection.add(((Number)n).longValue());
        return intersection;
    }
    private static List<Map<String,Object>> select(List<Object> roads,String name) {
        List<String> tokens=GeoDictionary.roadName(name);if(tokens.isEmpty())return List.of();
        List<Map<String,Object>> result=new ArrayList<>();
        for(Object raw:roads) {
            Map<String,Object> way=Json.object(raw);
            if(tokens.equals(GeoDictionary.roadName(Json.string(way.get("name"))))) {result.add(way);continue;}
            for(Object alias:Json.array(way.getOrDefault("aliases",List.of())))
                if(tokens.equals(GeoDictionary.roadName(Json.string(alias)))) {result.add(way);break;}
        }
        return result;
    }
    private static List<Long> shortest(Map<Long,List<Edge>> graph,Set<Long> starts,Set<Long> ends) {
        PriorityQueue<State> queue=new PriorityQueue<>(Comparator.comparingDouble(State::distance));
        Map<Long,Double> dist=new HashMap<>();Map<Long,Long> previous=new HashMap<>();
        for(long start:starts){dist.put(start,0d);queue.add(new State(start,0));}
        Long finish=null;
        while(!queue.isEmpty()){
            State s=queue.poll();if(s.distance()>dist.getOrDefault(s.node(),Double.POSITIVE_INFINITY))continue;
            if(ends.contains(s.node())){finish=s.node();break;}
            for(Edge e:graph.getOrDefault(s.node(),List.of())) {
                double next=s.distance()+e.meters();
                if(next<dist.getOrDefault(e.id(),Double.POSITIVE_INFINITY)) {
                    dist.put(e.id(),next);previous.put(e.id(),s.node());queue.add(new State(e.id(),next));
                }
            }
        }
        if(finish==null)return List.of();
        LinkedList<Long> route=new LinkedList<>();
        for(Long node=finish;node!=null;node=previous.get(node)) {
            route.addFirst(node);
            if(route.size()>30_000)return List.of();
        }
        return route;
    }
    private static double distance(Point p,Point q) {
        double meanLat=(p.lat+q.lat)*Math.PI/360;
        double x=(p.lon-q.lon)*111_320*Math.cos(meanLat),y=(p.lat-q.lat)*111_320;
        return Math.hypot(x,y);
    }
}
