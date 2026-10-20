package ru.activisthelp;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** A lightweight inverted gazetteer: cadastral IDs + OSM street aliases -> watch areas.
 * Text matches are review leads, never proof that construction is inside a polygon.
 */
public final class GeoDictionary {
    private static final Pattern CAD=Pattern.compile("(?<![0-9])([0-9]{2})\\s*:\\s*([0-9]{2})\\s*:\\s*([0-9]{6,7})\\s*:\\s*([0-9]{1,12})(?![0-9])");
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+",Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> ROAD_TYPES=Set.of("улица","ул","проспект","пр","пркт","переулок","пер","проезд","шоссе","тракт","площадь","пл","бульвар","бул","набережная","наб","аллея","дорога","переезд","тупик");
    private static final Set<String> COMMON=Set.of("мир","мира","победа","победы","дружба","дружбы","ленин","ленина","центральн","советск","школьн","нов","парков","строител","северн","южн","полевая");

    private record Ref(String areaId,String areaName,String value,String source,String certainty) {}
    private record StreetRef(Ref ref,List<String> words) {}
    private final Map<String,List<Ref>> cadastral=new HashMap<>();
    private final Map<String,List<StreetRef>> streets=new HashMap<>();
    private final List<Map<String,Object>> areas;

    public GeoDictionary(List<Map<String,Object>> areas) {
        this.areas=areas;
        for(Map<String,Object> a:areas) {
            String id=Json.string(a.get("id")),name=Json.string(a.get("name"));
            for(Object raw:Json.array(a.getOrDefault("parcels",List.of()))) {
                Map<String,Object> p=Json.object(raw);
                String number=Json.string(p.get("cadastralNumber"));
                Ref ref=new Ref(id,name,number,Json.string(p.get("source")),
                    "INTERSECTS_GEOMETRY".equals(p.get("zoneRelation"))?"IMPORTED_GEOMETRY":"AREA_DECLARED");
                cadastral.computeIfAbsent(number,k->new ArrayList<>()).add(ref);
            }
            for(Object raw:Json.array(a.getOrDefault("streets",List.of()))) {
                Map<String,Object> way=Json.object(raw);
                LinkedHashSet<String> aliases=new LinkedHashSet<>();
                aliases.add(Json.string(way.get("name")));
                for(Object alt:Json.array(way.getOrDefault("aliases",List.of())))aliases.add(Json.string(alt));
                for(String alias:aliases) {
                    List<String> tokens=roadName(alias);
                    if(tokens.isEmpty())continue;
                    Ref ref=new Ref(id,name,Json.string(way.get("name")),"OpenStreetMap", "STREET_NAME");
                    StreetRef street=new StreetRef(ref,tokens);
                    String head=tokens.get(0);
                    List<StreetRef> values=streets.computeIfAbsent(head,k->new ArrayList<>());
                    if(values.stream().noneMatch(s->s.ref.equals(ref)&&s.words.equals(tokens)))values.add(street);
                }
            }
        }
    }

    /** Finds watch zones plausibly related to the text and supplies traceable match evidence. */
    public List<Map<String,Object>> find(Map<String,Object> event) {
        String text=String.join(" ",Json.string(event.get("title")),Json.string(event.get("description")),
            Json.string(event.get("evidenceText")),Json.string(event.get("locationScope")),
            Json.string(event.get("workLocation")),Json.string(event.get("documentText")));
        Map<String,List<Map<String,Object>>> found=new LinkedHashMap<>();
        Matcher ids=CAD.matcher(text);
        while(ids.find()){
            String cad=ids.group(1)+":"+ids.group(2)+":"+ids.group(3)+":"+ids.group(4);
            for(Ref ref:cadastral.getOrDefault(cad,List.of())) {
                int score=ref.certainty.equals("IMPORTED_GEOMETRY")?90:65;
                add(found,ref,"CADASTRAL",cad,score,ref.certainty,
                    score==90?"Номер участка привязан к зоне по импортированной геометрии; границы самих работ не определены":"Номер участка вручную отнесён к зоне; геометрия не проверена");
            }
        }
        List<String> words=words(text);
        for(int i=0;i<words.size();i++) {
            String head=stem(words.get(i));
            for(StreetRef ref:streets.getOrDefault(head,List.of())) {
                List<String> key=ref.words;
                if(key.size()>words.size()-i)continue;
                boolean equal=true;
                for(int j=0;j<key.size();j++)if(!key.get(j).equals(stem(words.get(i+j)))){equal=false;break;}
                if(!equal)continue;
                boolean typed=hasRoadType(words,i,i+key.size());
                // A common one-word road name like 'Мира' is not enough without 'улица', 'проспект' etc.
                if(key.size()==1 && (key.get(0).length()<=4||COMMON.contains(key.get(0)))&&!typed)continue;
                int score=typed?48:32;
                add(found,ref.ref,"STREET",String.join(" ",words.subList(i,i+key.size())),score,
                    typed?"STREET_WITH_TYPE":"STREET_NAME_ONLY",
                    "Улица из OSM пересекает зону; в документе не установлены границы работ");
            }
        }
        for(Map<String,Object> area:areas) {
            String kws=Json.string(area.get("keywords"));
            for(String word:kws.split("[,;\\n]")) {
                if(Store.keywordHint(text,word)) {
                    Ref ref=new Ref(Json.string(area.get("id")),Json.string(area.get("name")),word.trim(),"user", "KEYWORD");
                    add(found,ref,"USER_KEYWORD",word.trim(),12,"KEYWORD_ONLY","Ручная текстовая метка без географического подтверждения");
                }
            }
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(var perArea:found.values())result.addAll(perArea);
        result.sort((a,b)->Integer.compare(((Number)b.get("score")).intValue(),((Number)a.get("score")).intValue()));
        return result;
    }
    private static void add(Map<String,List<Map<String,Object>>> out,Ref ref,String kind,String matched,int score,String certainty,String note) {
        List<Map<String,Object>> evidence=out.computeIfAbsent(ref.areaId,k->new ArrayList<>());
        for(Map<String,Object> previous:evidence)
            if(previous.get("kind").equals(kind)&&previous.get("object").equals(ref.value))return;
        if(evidence.size()>=8)return;
        evidence.add(Json.obj("areaId",ref.areaId,"area",ref.areaName,"kind",kind,"object",ref.value,
                "matchedText",matched,"score",score,"certainty",certainty,"source",ref.source,"explanation",note));
    }
    private static boolean hasRoadType(List<String> words,int from,int end) {
        for(int i=Math.max(0,from-3);i<Math.min(words.size(),end+3);i++)
            if(ROAD_TYPES.contains(normalRoadType(words.get(i))))return true;
        return false;
    }
    private static String normalRoadType(String word) {
        return switch(word) {
            case "улицы","улицу","улице","улицей","улиц" -> "улица";
            case "проспекта","проспекте","проспекту","проспектом" -> "проспект";
            case "переулка","переулке","переулку" -> "переулок";
            case "ш","шос","шосс" -> "шоссе";
            case "дороги","дороге","дорогу" -> "дорога";
            case "площади","площадью" -> "площадь";
            default -> word;
        };
    }
    static List<String> roadName(String name) {
        List<String> tokens=words(name),base=new ArrayList<>();
        for(String tok:tokens){String type=normalRoadType(tok);if(ROAD_TYPES.contains(type))continue;base.add(stem(tok));}
        if(base.size()>8||base.isEmpty())return List.of();
        return base;
    }
    static List<String> words(String text) {
        String low=Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ё','е'),Normalizer.Form.NFKC);
        Matcher matcher=WORD.matcher(low);List<String> out=new ArrayList<>();
        while(matcher.find()&&out.size()<20_000)out.add(matcher.group());
        return out;
    }
    private static String stem(String word) {
        // Conservative adjective case normalization; never turn the stem into a spatial proof.
        for(String ending:List.of("ского","скому","ском","ской","скую","ская","ское","ский","ские","ными","ного","ной","ную","ная","ное","ого","ому","ая","ой","ую","ое"))
            if(word.endsWith(ending)&&word.length()-ending.length()>=(ending.length()>2?3:4))return word.substring(0,word.length()-ending.length());
        return word;
    }
    public static boolean validCadastral(String number) {
        return number!=null&&number.matches("[0-9]{2}:[0-9]{2}:[0-9]{6,7}:[0-9]{1,12}");
    }
}
