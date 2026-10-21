package ru.activisthelp;

import java.nio.file.*;
import java.util.*;

/** Test excerpts are based on titles of actual Kazan decrees, not on downloaded OSM geometry. */
public final class LinearProjectTest {
    private static Map<String,Object> zone(double a,double b,double c,double d) {
        return Json.obj("type","Polygon","coordinates",List.of(List.of(
            List.of(a,b),List.of(c,b),List.of(c,d),List.of(a,d),List.of(a,b))));
    }
    private static Map<String,Object> way(long id,String name,long[] ids,double[][] coords) {
        List<Object> nodes=new ArrayList<>(),geometry=new ArrayList<>();
        for(int i=0;i<ids.length;i++) {nodes.add(ids[i]);geometry.add(List.of(coords[i][0],coords[i][1]));}
        return Json.obj("osmId",String.valueOf(id),"name",name,"aliases",List.of(),
            "geometry",geometry,"nodes",nodes,"kind","residential");
    }
    private static final String BATYRSHINA="Об утверждении проекта планировки и межевания линейного объекта «Реконструкция ул.Батыршина от ул.Галимджана Баруди до ул.Большая Крыловка в Кировском районе г.Казани»";
    private static final String I1="Автомобильная дорога от ул.Несмелова до М-7 через «Серебряный бор». I этап: участок №1 от ул.Несмелова до старого русла реки Казанки";
    private static final String II="Автомобильная дорога от ул.Несмелова до М-7 через «Серебряный бор». II этап – от жилого комплекса «Серебряный бор» до автомобильной дороги федерального значения М-7 «Волга»";
    private static void ok(boolean value,String what){if(!value)throw new AssertionError(what);}
    public static void main(String[] args)throws Exception {
        LinearWork.Spec s=LinearWork.parse(BATYRSHINA);
        ok(s!=null && "CROSS_STREETS".equals(s.type()),"named cross streets: "+s);
        ok("Батыршина".equals(s.mainRoad()),"main road: "+s);
        ok("Галимджана Баруди".equals(s.from().name()),"from: "+s);
        ok("Большая Крыловка".equals(s.to().name()),"to: "+s);
        LinearWork.Spec full=LinearWork.parse("Постановление Исполнительного комитета г.Казани от 19.05.2026 №1708. " +BATYRSHINA);
        ok(full!=null&&"CROSS_STREETS".equals(full.type())&&"Батыршина".equals(full.mainRoad()),
            "decree's signing date must NOT be interpreted as 'from road A': "+full);
        LinearWork.Spec stage=LinearWork.parse(II);
        ok(stage!=null && stage.stageSpecific(),"stage II exists");
        ok(!stage.scope().contains("Несмелова"),"stage-specific scope must not include parent project's street");
        ok("LANDMARK_RANGE".equals(stage.type()),"stage II endpoints are landmarks, not two streets");
        ok(LinearWork.parse(I1).scope().contains("Несмелова"),"I stage 1 includes Nesmelova");
        ok(LinearWork.parse("Ремонт улицы от дома 10 до дома 20").type().equals("HOUSE_RANGE"),"house number parsing");
        ok(LinearWork.parse("Ремонт М-7 на участке км 2+400 – км 5+050").toKm()==5050,"chainage parsing");
        ok(LinearWork.parse("Городская программа на 2026 год")==null,"not every document is a span");

        // Synthetic OSM: split main street, both crossings share actual OSM node IDs.
        List<Map<String,Object>> network=List.of(
            way(1,"улица Батыршина",new long[]{100,102,104,106},new double[][]{{0,5},{2,5},{4,5},{6,5}}),
            way(2,"улица Батыршина",new long[]{106,108,110},new double[][]{{6,5},{8,5},{10,5}}),
            way(3,"улица Галимджана Баруди",new long[]{201,102,202},new double[][]{{2,0},{2,5},{2,10}}),
            way(4,"улица Большая Крыловка",new long[]{301,108,302},new double[][]{{8,0},{8,5},{8,10}}));
        Map<String,Object> a=Json.obj("id","a","name","Inside","geometry",zone(3,4,5,6),"contextWays",network);
        Map<String,Object> b=Json.obj("id","b","name","Outside","geometry",zone(8.5,4,9.5,6),"contextWays",network);
        Map<String,Object> hit=RoadCorridor.assess(s,a),outside=RoadCorridor.assess(s,b);
        ok("INTERSECTS_INFERRED".equals(hit.get("status")),"line intersects watched polygon: "+hit);
        ok("OUTSIDE_INFERRED".equals(outside.get("status")),"line outside polygon: "+outside);
        ok(((Number)hit.get("lengthMeters")).intValue()>0,"route length");
        // An overpass-looking geometric crossing with unrelated node IDs is NOT a junction.
        List<Map<String,Object>> bridge=new ArrayList<>(network);
        bridge.set(2,way(3,"улица Галимджана Баруди",new long[]{201,299,202},
            new double[][]{{2,0},{2,5},{2,10}}));
        Map<String,Object> overpass=Json.obj("id","o","name","Bridge","geometry",zone(3,4,5,6),"contextWays",bridge);
        ok("UNRESOLVED".equals(RoadCorridor.assess(s,overpass).get("status")),"different-level crossing not invented");

        Path dir=Files.createTempDirectory("ucat-projects-");
        try {
            Store store=new Store(dir);
            Map<String,Object> watch=store.area(Json.obj("name","Улица Несмелова (контрольная зона)",
                "geometry",zone(49.06,55.82,49.08,55.84),"keywords","Несмелова"));
            Map<String,Object> i=store.event(Json.obj("title",I1,"source","ARCHIVE","externalId","kzn-1726"));
            Map<String,Object> ii=store.event(Json.obj("title",II,"source","ARCHIVE","externalId","kzn-3763"));
            List<Map<String,Object>> views=store.view();
            Map<String,Object> fi=views.stream().filter(x->x.get("id").equals(i.get("id"))).findFirst().orElseThrow();
            Map<String,Object> fii=views.stream().filter(x->x.get("id").equals(ii.get("id"))).findFirst().orElseThrow();
            ok("HINT".equals(fi.get("relevance")),"stage I near Nesmelova: "+fi.get("relevance"));
            ok("UNLOCATED".equals(fii.get("relevance")),"stage II should not inherit parent's street: "+fii.get("relevance"));
            Map<String,Object> project=store.createProject(Json.obj("name","Несмелова — М-7","notes","One road, several stages"));
            String projectId=Json.string(project.get("id"));
            store.linkProject(projectId,Json.string(ii.get("id")),true);
            store.linkProject(projectId,Json.string(i.get("id")),true);
            ok(Json.array(store.project(projectId).get("timeline")).size()==2,"two official documents in project history");
            store.linkProject(projectId,Json.string(i.get("id")),true);
            ok(Json.array(store.project(projectId).get("timeline")).size()==2,"no duplicate link");
            Store reload=new Store(dir);
            ok(reload.projects().size()==1,"project persisted");
            ok(Json.array(reload.project(projectId).get("timeline")).size()==2,"project links persisted");
            reload.linkProject(projectId,Json.string(i.get("id")),false);
            ok(Json.array(reload.project(projectId).get("timeline")).size()==1,"project unlink works");
            Map<String,Object> base=reload.event(Json.obj("title","Постановление Исполнительного комитета г.Казани от 16.03.2026 №793 Об утверждении планировки","source","KZN_ACT"));
            Map<String,Object> change=reload.event(Json.obj("title","О внесении изменений в проект планировки, утвержденный постановлением Исполнительного комитета г.Казани от 16.03.2026 №793","source","KZN_ACT"));
            List<Map<String,Object>> links=ProjectLinks.candidates(Json.string(base.get("id")),reload.view());
            ok(links.stream().anyMatch(x->"REFERENCES_ACT".equals(x.get("relation"))&&change.get("id").equals(x.get("eventId"))),"explicit act reference candidates: "+links);
        } finally {
            Files.deleteIfExists(dir.resolve("activist.json"));Files.deleteIfExists(dir);
        }
        System.out.println("LinearProjectTest: all assertions passed.");
    }
}
