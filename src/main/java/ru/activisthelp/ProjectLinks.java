package ru.activisthelp;

import java.util.*;
import java.util.regex.*;

/**
 * Candidate document relationships, with independently inspectable evidence.
 * A common street or official-number mention alone never automatically merges projects.
 */
public final class ProjectLinks {
    private ProjectLinks() {}
    private static final Pattern REFERENCE=Pattern.compile("(?iuU)(?:утвержденн(?:ый|ого|ом|ая)|внесени[ея]\\s+изменени[йя])"+
        ".{0,190}?(?:постановлени\\p{L}{1,4}|от\\s+\\d{2}\\.\\d{2}\\.\\d{4}).{0,140}?№\\s*(\\d{1,6})");
    private static final Pattern ACT=Pattern.compile("(?iuU)^(?:постановление|распоряжение).{0,130}?\\bот\\s+\\d{2}\\.\\d{2}\\.\\d{4}\\s*№\\s*(\\d{1,6})");
    public static List<Map<String,Object>> candidates(String eventId,List<Map<String,Object>> events) {
        Map<String,Object> target=null;
        for(var event:events)if(eventId.equals(event.get("id"))) {target=event;break;}
        if(target==null)return List.of();
        Map<String,Set<String>> a=identifiers(target);
        String aAct=act(target);Set<String> aRefs=references(target);
        LinearWork.Spec aSpan=LinearWork.parse(Json.string(target.get("title")));
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> bEvent:events) {
            if(eventId.equals(bEvent.get("id")))continue;
            Map<String,Set<String>> b=identifiers(bEvent);
            Set<String> parcels=new LinkedHashSet<>(a.get("CADASTRAL"));parcels.retainAll(b.get("CADASTRAL"));
            Set<String> roads=new LinkedHashSet<>(a.get("STREET"));roads.retainAll(b.get("STREET"));
            String bAct=act(bEvent);Set<String> bRefs=references(bEvent);
            boolean refers=(!aAct.isEmpty()&&bRefs.contains(aAct))||(!bAct.isEmpty()&&aRefs.contains(bAct));
            LinearWork.Spec bSpan=LinearWork.parse(Json.string(bEvent.get("title")));
            boolean sameSection=aSpan!=null&&bSpan!=null&&"CROSS_STREETS".equals(aSpan.type())
                &&"CROSS_STREETS".equals(bSpan.type())
                &&name(aSpan.mainRoad()).equals(name(bSpan.mainRoad()))
                &&name(aSpan.from().name()).equals(name(bSpan.from().name()))
                &&name(aSpan.to().name()).equals(name(bSpan.to().name()))
                &&!name(aSpan.mainRoad()).isEmpty();
            if(parcels.isEmpty()&&roads.isEmpty()&&!refers&&!sameSection)continue;
            int score=(parcels.isEmpty()?0:70)+(roads.isEmpty()?0:25);
            if(sameSection)score=Math.max(score,80);
            if(refers)score=Math.max(score,96);
            String sa=Json.string(target.get("stage")),sb=Json.string(bEvent.get("stage"));
            if(!sa.equals(sb)&&!sa.isBlank()&&!sb.isBlank())score+=5;
            String relation=refers?"REFERENCES_ACT":sameSection?"SHARED_LINEAR_SECTION":
                !parcels.isEmpty()&&!roads.isEmpty()?"SHARED_PARCEL_AND_STREET":
                !parcels.isEmpty()?"SHARED_PARCEL":"SHARED_STREET";
            String explanation=refers?"В тексте одного документа обнаружена ссылка на номер акта другого; проверьте год, орган и предмет":
                sameSection?"Одинаковая текстовая граница дороги; проверьте, что это один проект, а не независимые работы":
                "Возможная связь по географическим объектам; это не подтверждение единого проекта";
            out.add(Json.obj("eventId",bEvent.get("id"),"title",bEvent.get("title"),
                "stage",sb,"score",score,"relation",relation,"sameParcels",new ArrayList<>(parcels),
                "sameStreets",new ArrayList<>(roads),"status","REVIEW","explanation",explanation));
        }
        out.sort((x,y)->Integer.compare(((Number)y.get("score")).intValue(),((Number)x.get("score")).intValue()));
        return out.size()>25?new ArrayList<>(out.subList(0,25)):out;
    }
    private static String name(String s){return String.join(" ",GeoDictionary.roadName(s));}
    private static String fields(Map<String,Object> e){return Json.string(e.get("title"))+" "+Json.string(e.get("description"));}
    private static String act(Map<String,Object> e) {
        String explicit=Json.string(e.get("actNumber"));if(explicit.matches("\\d{1,6}"))return explicit;
        Matcher m=ACT.matcher(fields(e));return m.find()?m.group(1):"";
    }
    private static Set<String> references(Map<String,Object> e) {
        Set<String> refs=new HashSet<>();Matcher m=REFERENCE.matcher(fields(e));
        while(m.find())refs.add(m.group(1));return refs;
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
