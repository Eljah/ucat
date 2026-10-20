package ru.activisthelp;

import java.util.*;

/** Candidate links between an act, its annex object, tender, and works stage.
 * Never merges multiple projects on the same road without human confirmation.
 */
public final class ProjectLinks {
    private ProjectLinks() {}
    public static List<Map<String,Object>> candidates(String eventId,List<Map<String,Object>> events) {
        Map<String,Object> target=null;
        for(var event:events)if(eventId.equals(event.get("id"))) {target=event;break;}
        if(target==null)return List.of();
        Map<String,Set<String>> a=identifiers(target);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> bEvent:events) {
            if(eventId.equals(bEvent.get("id")))continue;
            Map<String,Set<String>> b=identifiers(bEvent);
            Set<String> parcels=new LinkedHashSet<>(a.get("CADASTRAL"));parcels.retainAll(b.get("CADASTRAL"));
            Set<String> roads=new LinkedHashSet<>(a.get("STREET"));roads.retainAll(b.get("STREET"));
            if(parcels.isEmpty()&&roads.isEmpty())continue;
            int score=(parcels.isEmpty()?0:70)+(roads.isEmpty()?0:25);
            // Temporal progression and multiple independent identifiers are better than one shared street.
            String sa=Json.string(target.get("stage")),sb=Json.string(bEvent.get("stage"));
            if(!sa.equals(sb)&&!sa.isBlank()&&!sb.isBlank())score+=5;
            String relation=!parcels.isEmpty()&&!roads.isEmpty()?"SHARED_PARCEL_AND_STREET":
                    !parcels.isEmpty()?"SHARED_PARCEL":"SHARED_STREET";
            out.add(Json.obj("eventId",bEvent.get("id"),"title",bEvent.get("title"),
                "stage",sb,"score",score,"relation",relation,"sameParcels",new ArrayList<>(parcels),
                "sameStreets",new ArrayList<>(roads),"status","REVIEW",
                "explanation","Возможная связь по объектам; это не подтверждение единого проекта"));
        }
        out.sort((x,y)->Integer.compare(((Number)y.get("score")).intValue(),((Number)x.get("score")).intValue()));
        return out.size()>25?new ArrayList<>(out.subList(0,25)):out;
    }
    private static Map<String,Set<String>> identifiers(Map<String,Object> event) {
        Set<String> c=new HashSet<>(),s=new HashSet<>();
        for(Object raw:Json.array(event.getOrDefault("geoEvidence",List.of()))) {
            Map<String,Object> e=Json.object(raw);
            String kind=Json.string(e.get("kind"));
            String token=Json.string(e.get("areaId"))+"|"+Json.string(e.get("object"));
            if("CADASTRAL".equals(kind))c.add(token);
            if("STREET".equals(kind))s.add(token);
        }
        return Map.of("CADASTRAL",c,"STREET",s);
    }
}
