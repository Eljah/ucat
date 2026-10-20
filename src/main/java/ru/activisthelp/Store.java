package ru.activisthelp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Single-user durable JSON store; atomic replacement on save. Not intended for a multi-user deployment. */
public final class Store {
    private final Path file;
    private final List<Map<String,Object>> areas=new ArrayList<>();
    private final List<Map<String,Object>> events=new ArrayList<>();

    public Store(Path directory) throws IOException {
        Files.createDirectories(directory);
        file=directory.resolve("activist.json");
        if(Files.exists(file)) {
            Map<String,Object> all=Json.object(Json.parse(Files.readString(file,StandardCharsets.UTF_8)));
            for(Object a:Json.array(all.getOrDefault("areas",List.of())))areas.add(Json.object(a));
            for(Object e:Json.array(all.getOrDefault("events",List.of())))events.add(Json.object(e));
        }
    }
    /** Browser gets compact area metadata, not thousands of OSM ways and cadastral footprints. */
    public synchronized List<Map<String,Object>> areas(){
        List<Map<String,Object>> out=new ArrayList<>();
        for(var area:areas)out.add(publicArea(area));
        return out;
    }
    private static Map<String,Object> publicArea(Map<String,Object> area) {
        Map<String,Object> view=new LinkedHashMap<>(area);
        List<Object> ways=Json.array(view.getOrDefault("streets",List.of()));
        List<Object> parcels=Json.array(view.getOrDefault("parcels",List.of()));
        view.remove("streets");view.remove("parcels");
        view.put("streetCount",ways.stream().map(s->Json.string(Json.object(s).get("name"))).distinct().count());
        view.put("parcelCount",parcels.size());
        return view;
    }
    /** Full backup, including authoritative imported source identifiers and geometry. */
    public synchronized List<Map<String,Object>> areaRecords(){return new ArrayList<>(areas);}
    public synchronized Map<String,Object> areaRecord(String id) {
        for(var area:areas)if(id.equals(area.get("id")))return area;
        return null;
    }
    public synchronized Map<String,Object> areaDictionary(String id) {
        Map<String,Object> area=areaRecord(id);
        if(area==null)return null;
        return Json.obj("areaId",id,"name",area.get("name"),"streets",area.getOrDefault("streets",List.of()),
            "parcels",area.getOrDefault("parcels",List.of()),"osmFetchedAt",area.getOrDefault("osmFetchedAt",""),
            "parcelsImportedAt",area.getOrDefault("parcelsImportedAt",""));
    }
    public synchronized Map<String,Object> updateStreets(String id,List<Map<String,Object>> streets)throws IOException {
        Map<String,Object> area=areaRecord(id);
        if(area==null)return null;
        // Replace OSM streets atomically, never confuse partial HTTP results with an empty street list.
        area.put("streets",streets);
        area.put("osmFetchedAt",Instant.now().toString());save();return publicArea(area);
    }
    /** Import GeoJSON cadastral polygons or manually assigned cadastral IDs into a watch zone. */
    public synchronized Map<String,Object> importParcels(String id,Map<String,Object> input)throws IOException {
        Map<String,Object> area=areaRecord(id);
        if(area==null)return null;
        List<Object> records;
        if("FeatureCollection".equals(input.get("type")))records=Json.array(input.get("features"));
        else records=Json.array(input.getOrDefault("parcels",List.of()));
        if(records.size()>300)throw new IllegalArgumentException("Не более 300 участков за запрос");
        LinkedHashMap<String,Map<String,Object>> existing=new LinkedHashMap<>();
        for(Object raw:Json.array(area.getOrDefault("parcels",List.of()))) {
            Map<String,Object> p=Json.object(raw);existing.put(Json.string(p.get("cadastralNumber")),p);
        }
        int accepted=0,skipped=0;
        for(Object raw:records) {
            Map<String,Object> item=Json.object(raw);
            Map<String,Object> props="Feature".equals(item.get("type"))?
                Json.object(item.getOrDefault("properties",Map.of())):item;
            String number=number(props).replaceAll("\\s+","").trim();
            if(!GeoDictionary.validCadastral(number)) {skipped++;continue;}
            Object rawGeom=item.get("geometry");
            if(rawGeom==null)rawGeom=props.get("geometry");
            String relation="AREA_DECLARED";
            if(rawGeom!=null){
                Map<String,Object> footprint=Json.object(rawGeom);Geo.validate(footprint);
                if(!Geo.intersectsPolygons(Json.object(area.get("geometry")),footprint)) {skipped++;continue;}
                relation="INTERSECTS_GEOMETRY";
            }
            String source=Json.string(input.getOrDefault("source","Пользовательский импорт GeoJSON/ID"));
            source=limit(source,120);
            Map<String,Object> older=existing.get(number);
            if(older!=null && "INTERSECTS_GEOMETRY".equals(older.get("zoneRelation")) && rawGeom==null) {
                accepted++;continue; // never overwrite a geometrically linked parcel with an ID-only import
            }
            existing.put(number,Json.obj("cadastralNumber",number,"geometry",rawGeom,
                "source",source,"zoneRelation",relation));
            accepted++;
        }
        if(existing.size()>6000)throw new IllegalArgumentException("Не более 6000 кадастровых номеров в одной зоне");
        area.put("parcels",new ArrayList<>(existing.values()));
        area.put("parcelsImportedAt",Instant.now().toString());save();
        return Json.obj("area",publicArea(area),"accepted",accepted,"skipped",skipped,
            "note","Номера без границ — ручная привязка, а не подтверждённое пространственное пересечение");
    }
    private static String number(Map<String,Object> data) {
        for(String alias:List.of("cadastralNumber","cadastral_number","cad_num","cadnum","cn","Кадастровый номер","кадастровый номер","кадастровый_номер"))
            if(data.containsKey(alias))return Json.string(data.get(alias));
        return "";
    }
    public synchronized List<Map<String,Object>> events(){return new ArrayList<>(events);}
    public synchronized Map<String,Object> area(Map<String,Object> input) throws IOException {
        String name=Json.string(input.get("name")).trim();
        if(name.isBlank()||name.length()>140)throw new IllegalArgumentException("Area name required (max 140 characters)");
        if(areas.size()>=200)throw new IllegalArgumentException("Maximum 200 watch areas");
        Map<String,Object> geometry=Json.object(input.get("geometry"));Geo.validate(geometry);
        String keywords=Json.string(input.get("keywords"));
        if(keywords.length()>500)throw new IllegalArgumentException("Keywords max length 500");
        Map<String,Object> area=Json.obj("id",UUID.randomUUID().toString(),"name",name,"geometry",geometry,
            "keywords",keywords,"createdAt",Instant.now().toString(),"streets",new ArrayList<>(),"parcels",new ArrayList<>());
        areas.add(area);save();return area;
    }
    public synchronized boolean removeArea(String id) throws IOException {
        boolean changed=areas.removeIf(a->id.equals(a.get("id")));
        if(changed)save();return changed;
    }
    public synchronized Map<String,Object> event(Map<String,Object> input) throws IOException {
        String title=Json.string(input.get("title")).trim();
        if(title.isBlank()||title.length()>700)throw new IllegalArgumentException("Event title required (max 700 characters)");
        String link=Json.string(input.get("url")).trim();
        if(!link.isEmpty() && !(link.startsWith("https://")||link.startsWith("http://")))
            throw new IllegalArgumentException("Source URL must be http(s)");
        String source=Json.string(input.getOrDefault("source","MANUAL"));
        if(source.length()>100)throw new IllegalArgumentException("Source too long");
        String externalId=limit(Json.string(input.get("externalId")).trim(),180);
        for(var existing:events) {
            String previousId=Json.string(existing.get("externalId"));
            if(!externalId.isBlank() && externalId.equals(previousId) && source.equals(existing.get("source")))return existing;
            // Multiple official acts may be printed in the same bulletin, and therefore have one URL.
            if(externalId.isBlank() && previousId.isBlank() && !link.isBlank() && link.equals(existing.get("url")))return existing;
        }
        if(events.size()>=10000)throw new IllegalArgumentException("Local prototype event limit 10000");
        Object position=input.get("position");
        if(position!=null)validatePosition(Json.array(position));
        String stage=Classification.stage(title);
        String explicitStage=Json.string(input.get("stage"));
        if(Set.of("DISCUSSION","PREPARATION","PROCUREMENT","APPROVAL","WORKS","OTHER").contains(explicitStage))stage=explicitStage;
        if("PROCUREMENT".equals(Json.string(input.get("documentKind"))))stage="PROCUREMENT";
        String observed=Instant.now().toString();
        Map<String,Object> ev=Json.obj("id",UUID.randomUUID().toString(),"title",title,"url",link,
            "source",source,"description",limit(Json.string(input.get("description")),1400),
            "stage",stage,"firstSeen",observed,"publishedAt",Json.string(input.get("publishedAt")),
            "deadline",Json.string(input.get("deadline")),"position",position,
            "accuracy",position==null?"UNKNOWN":Json.string(input.getOrDefault("accuracy","MANUAL")),
            "seen",false,"dismissed",false,"note","","demo",Boolean.TRUE.equals(input.get("demo")),
            "externalId",externalId,"issuedAt",limit(Json.string(input.get("issuedAt")),35),
            "locationScope",limit(Json.string(input.get("locationScope")),80),
            "program",limit(Json.string(input.get("program")),200),
            "authority",limit(Json.string(input.get("authority")),250),
            "documentKind",limit(Json.string(input.get("documentKind")),100),
            "annexPage",input.get("annexPage") instanceof Number ? ((Number)input.get("annexPage")).intValue() : null,
            "evidenceText",limit(Json.string(input.get("evidenceText")),700),
            "requiresReview",Boolean.TRUE.equals(input.get("requiresReview")),
            "workLocation",limit(Json.string(input.get("workLocation")),400),
            "documentText",limit(Json.string(input.get("documentText")),12000));
        events.add(0,ev);save();return ev;
    }
    private static String limit(String s,int n){return s.length()<=n?s:s.substring(0,n);}
    private static void validatePosition(List<Object> point) {
        if(point.size()!=2)throw new IllegalArgumentException("Position must be [lon,lat]");
        double x=Geo.num(point.get(0)),y=Geo.num(point.get(1));
        if(!Double.isFinite(x)||!Double.isFinite(y)||x<-180||x>180||y<-90||y>90)
            throw new IllegalArgumentException("Invalid position");
    }
    public synchronized Map<String,Object> updateEvent(String id,Map<String,Object> patch) throws IOException {
        for(var event:events) {
            if(!id.equals(event.get("id")))continue;
            if(patch.containsKey("position")) {
                Object position=patch.get("position");
                if(position!=null)validatePosition(Json.array(position));
                event.put("position",position);event.put("accuracy",position==null?"UNKNOWN":"MANUAL");
            }
            if(patch.containsKey("seen"))event.put("seen",Boolean.TRUE.equals(patch.get("seen")));
            if(patch.containsKey("dismissed"))event.put("dismissed",Boolean.TRUE.equals(patch.get("dismissed")));
            if(patch.containsKey("note"))event.put("note",limit(Json.string(patch.get("note")),2500));
            if(patch.containsKey("deadline")) {
                String deadline=Json.string(patch.get("deadline"));
                if(!deadline.isBlank() && !deadline.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException("Deadline must be YYYY-MM-DD");
                event.put("deadline",deadline);
            }
            save();return event;
        }
        return null;
    }
    public synchronized List<Map<String,Object>> view() {
        GeoDictionary index=new GeoDictionary(areas);
        List<Map<String,Object>> out=new ArrayList<>();
        for(var e:events) {
            Map<String,Object> copy=new LinkedHashMap<>(e);
            List<String> matches=new ArrayList<>(),hints=new ArrayList<>();
            List<Map<String,Object>> evidence=index.find(e);
            for(var hit:evidence) {
                String name=Json.string(hit.get("area"));
                if(!hints.contains(name))hints.add(name);
            }
            Object p=e.get("position");
            if(p!=null) {
                List<Object> position=Json.array(p);
                for(var a:areas) {
                    try {
                        if(Geo.contains(Json.object(a.get("geometry")),Geo.num(position.get(0)),Geo.num(position.get(1))))
                            matches.add(Json.string(a.get("name")));
                    }catch(IllegalArgumentException ignored){}
                }
                copy.put("relevance",matches.isEmpty()?"OUTSIDE":"MATCH");
            } else copy.put("relevance",hints.isEmpty()?"UNLOCATED":"HINT");
            copy.put("areas",matches);copy.put("hints",hints);
            copy.put("geoEvidence",evidence);
            out.add(copy);
        }
        return out;
    }
    /** A permissive Russian word-stem hint, never considered a confirmed spatial match. */
    static boolean keywordHint(String text,String keyword) {
        String normalizedText=text.toLowerCase(Locale.ROOT).replace('ё','е');
        String normalizedWord=keyword.toLowerCase(Locale.ROOT).replace('ё','е');
        if(normalizedWord.trim().length()<3)return false;
        if(GeoDictionary.validCadastral(normalizedWord.trim()))
            return java.util.regex.Pattern.compile("(?<![0-9])"+java.util.regex.Pattern.quote(normalizedWord.trim())+"(?![0-9])")
                .matcher(normalizedText).find();
        if(normalizedText.contains(normalizedWord.trim()))return true;
        String[] words=normalizedWord.split("[^\\p{L}0-9]+");
        int matched=0;
        for(String word:words) {
            if(word.length()<4)continue;
            String stem=word.substring(0,Math.min(6,word.length()-1));
            if(!normalizedText.contains(stem))return false;
            matched++;
        }
        return matched>0;
    }
    private void save() throws IOException {
        Path temp=file.resolveSibling(file.getFileName()+".tmp");
        Files.writeString(temp,Json.stringify(Json.obj("version",1,"areas",areas,"events",events)), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try {Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
    public synchronized void seed() throws IOException {
        if(!events.isEmpty()||!areas.isEmpty())return;
        area(Json.obj("name","Учебная территория (демо)","keywords","межевание;Западное Заречье;Горьковское шоссе",
            "geometry",Json.obj("type","Polygon","coordinates",List.of(List.of(
                List.of(49.057,55.822),List.of(49.092,55.822),List.of(49.092,55.837),
                List.of(49.057,55.837),List.of(49.057,55.822))))));
        event(Json.obj("title","Учебный пример: проектирование велодорожки (НЕ реальное событие)","source","DEMO",
            "description","Синтетический пример с точкой внутри учебной территории. Удалите тестовую территорию после знакомства с интерфейсом.",
            "position",List.of(49.076,55.83),"demo",true));
        event(Json.obj("title","Оповещение о начале обсуждений: изменение проекта планировки Западного Заречья",
            "source","DISPUTE_EXAMPLE","url","https://dispute.kzn.ru/",
            "description","Пример реального типа оповещения от 13.10.2026. Точная геометрия пока не определена; проверьте исходный документ.","publishedAt","2026-10-13","demo",true));
        event(Json.obj("title","Оповещение о начале обсуждений: проект межевания по ул. Задорная",
            "source","DISPUTE_EXAMPLE","url","https://dispute.kzn.ru/disputes/", 
            "description","Пример реального типа оповещения от 15.10.2026. Официальная ссылка ведёт на перечень; сначала проверьте конкретную карточку.","publishedAt","2026-10-15","demo",true));
    }
}
