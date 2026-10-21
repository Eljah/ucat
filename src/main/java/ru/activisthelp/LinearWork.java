package ru.activisthelp;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/**
 * Extracts the local scope of a road project from Russian document titles.
 * This is document interpretation, NOT a legal/geometric confirmation of work limits.
 * A named II stage must not inherit the entire route of the parent project.
 */
public final class LinearWork {
    private LinearWork() {}
    private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
    private static final Pattern STAGE=Pattern.compile("(?iuU)(?:\\b(?:I|II|III|IV|V|VI|VII|VIII|IX|X|\\d+)\\s*[-–.]?\\s*этап(?:а|е|ом)?\\b|\\bучасток\\s*№?\\s*\\d+\\b)");
    private static final Pattern FROM_TO=Pattern.compile("(?iuU)\\bот\\s+(?!\\d{2}\\.\\d{2}\\.\\d{4})(.{2,180}?)\\s+до\\s+(.{2,220})", Pattern.DOTALL);
    private static final Pattern BETWEEN=Pattern.compile("(?iuU)\\bмежду\\s+(.{2,120}?)\\s+и\\s+(.{2,160})", Pattern.DOTALL);
    private static final Pattern KM=Pattern.compile("(?iuU)\\bкм\\.?\\s*(\\d{1,4})\\s*\\+\\s*(\\d{1,3})\\s*(?:[-–—]|до|по)\\s*(?:км\\.?)?\\s*(\\d{1,4})\\s*\\+\\s*(\\d{1,3})");
    private static final Pattern HOUSES=Pattern.compile("(?iuU)\\bот\\s+(?:дома|д\\.)\\s*№?\\s*([0-9]+[а-яa-z]?(?:/[0-9]+)?)\\s+до\\s+(?:дома|д\\.)\\s*№?\\s*([0-9]+[а-яa-z]?(?:/[0-9]+)?)");
    private static final Pattern ROAD=Pattern.compile("(?iuU)^(?:ул\\.?|улиц(?:а|ы|е|у|ей)|проспект(?:а|у|е)?|пр-?т\\.?|пер\\.?|переул(?:ок|ка|ке)|шоссе|тракт(?:а|е)?|проезд(?:а|е)?|площад(?:ь|и)|наб(?:ережная|ережной|ережную|\\.)?)\\s*(.{2,125})$");
    private static final Pattern MAIN=Pattern.compile("(?iuU)(?:ул\\.?|улиц(?:а|ы|е|у|ей)|проспект(?:а|у|е)?|пр-?т\\.?|пер\\.?|переул(?:ок|ка|ке)|шоссе|тракт(?:а|е)?|проезд(?:а|е)?)\\s*([\\p{L}0-9][\\p{L}0-9\\s-]{1,85}?)\\s*$");
    private static final Pattern REGION=Pattern.compile("(?iuU)\\s+(?:в|на)\\s+(?:Кировском|Вахитовском|Советском|Приволжском|Авиастроительном|Московском|Ново-Савиновском)\\s+районе.*$");
    private static final Pattern QUALIFIER=Pattern.compile("(?iuU)\\s+(?:в\\s+г\\.?\\s*Казани|в\\s+городе\\s+Казани|Республики\\s+Татарстан|согласно\\s+постановлению|утвержденн(?:ый|ого|ом|ая)).*$");

    public record Endpoint(String name, String kind) {
        Map<String,Object> json(){return Json.obj("name",name,"kind",kind);}
    }
    public record Spec(String scope, String stage, String mainRoad, Endpoint from, Endpoint to,
                       String type, Integer fromKm, Integer toKm, boolean stageSpecific) {
        public Map<String,Object> json() {
            return Json.obj("scope",scope,"stage",stage,"mainRoad",mainRoad,
                "from",from==null?null:from.json(),"to",to==null?null:to.json(),
                "type",type,"fromKm",fromKm,"toKm",toKm,"stageSpecific",stageSpecific,
                "note","Границы извлечены из текста. Геометрию требуется проверять по проекту и OSM.");
        }
    }
    public static Spec parse(String input) {
        if(input==null||input.isBlank())return null;
        String normalized=Normalizer.normalize(input.replace('\u00a0',' '),Normalizer.Form.NFC)
                .replaceAll("[\\r\\n\\t]+"," ").replaceAll("\\s+"," ").trim();
        if(normalized.length()>12_000)normalized=normalized.substring(0,12_000);
        Matcher stageMatcher=STAGE.matcher(normalized);
        int stageStart=-1,stageEnd=-1;String stage="";
        while(stageMatcher.find()){stageStart=stageMatcher.start();stageEnd=stageMatcher.end();stage=stageMatcher.group().trim();}
        String scoped=stageEnd<0?normalized:normalized.substring(stageEnd).replaceFirst("^[\\s.:–—-]+","");
        // Clauses after the latest stage define its spatial extent, not the route named before that stage.
        // For acts that merely cite another decree, only a structured span counts as a segment.
        Matcher m=FROM_TO.matcher(scoped);String before="";String a="",b="";
        if(m.find()) {before=scoped.substring(0,m.start());a=m.group(1);b=m.group(2);}
        else {
            Matcher between=BETWEEN.matcher(scoped);
            if(between.find()){before=scoped.substring(0,between.start());a=between.group(1);b=between.group(2);}
        }
        String main=mainRoad(before);
        Matcher km=KM.matcher(scoped);
        if(km.find()) {
            int first=1000*Integer.parseInt(km.group(1))+Integer.parseInt(km.group(2));
            int last=1000*Integer.parseInt(km.group(3))+Integer.parseInt(km.group(4));
            if(last<first||last-first>200_000)return null;
            return new Spec(cleanScope(scoped),stage,main,null,null,"CHAINAGE",first,last,stageEnd>=0);
        }
        Matcher house=HOUSES.matcher(scoped);
        if(house.find())return new Spec(cleanScope(scoped),stage,main,
            new Endpoint(house.group(1),"HOUSE"),new Endpoint(house.group(2),"HOUSE"),
            "HOUSE_RANGE",null,null,stageEnd>=0);
        if(!a.isBlank()&&!b.isBlank()) {
            Endpoint from=endpoint(a),to=endpoint(b);
            if(from.name().isBlank()||to.name().isBlank())return null;
            String type=from.kind().equals("STREET")&&to.kind().equals("STREET")?
                "CROSS_STREETS":"LANDMARK_RANGE";
            return new Spec(cleanScope(scoped),stage,main,from,to,type,null,null,stageEnd>=0);
        }
        return stageEnd>=0?new Spec(cleanScope(scoped),stage,main,null,null,"STAGE_UNLOCATED",null,null,true):null;
    }
    private static String mainRoad(String before) {
        Matcher m=MAIN.matcher(before);
        return m.find()?cleanName(m.group(1)):"";
    }
    private static Endpoint endpoint(String raw) {
        String value=cleanName(raw);
        Matcher road=ROAD.matcher(value);
        if(road.matches())return new Endpoint(cleanName(road.group(1)),"STREET");
        if(value.matches("(?iuU)^(?:автомобильн.*дорог.*|дорог.*(?:М-?\\d+|Р-?\\d+).*)"))return new Endpoint(value,"ROAD_REFERENCE");
        return new Endpoint(value,"LANDMARK");
    }
    private static String cleanName(String s) {
        String t=s.replaceAll("^[\\s«\"“]+","").replaceAll("[\\s»\"”]+$","");
        t=REGION.matcher(t).replaceFirst("");t=QUALIFIER.matcher(t).replaceFirst("");
        t=t.replaceAll("(?iuU)\\s+(?:в\\s+Кировском|в\\s+Советском).*","");
        t=t.replaceAll("[;,].*$","").replaceAll("\\s+"," ").trim();
        t=t.replaceAll("[.\\s]+$","");
        return t.length()>150?t.substring(0,150):t;
    }
    private static String cleanScope(String s){return s.length()>500?s.substring(0,500):s;}
}
