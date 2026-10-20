package ru.activisthelp;

import java.nio.file.*;
import java.util.*;

/** Self-contained regression tests: no Overpass server, NСПД account, or live data needed. */
public final class GeoEnrichmentTest {
    private static Map<String,Object> polygon(double x1,double y1,double x2,double y2) {
        return Json.obj("type","Polygon","coordinates",List.of(List.of(
            List.of(x1,y1),List.of(x2,y1),List.of(x2,y2),List.of(x1,y2),List.of(x1,y1))));
    }
    public static void main(String[] args)throws Exception {
        Map<String,Object> outer=Json.obj("type","Polygon","coordinates",List.of(
            List.of(List.of(0,0),List.of(10,0),List.of(10,10),List.of(0,10),List.of(0,0)),
            List.of(List.of(3,3),List.of(7,3),List.of(7,7),List.of(3,7),List.of(3,3))));
        assert Geo.intersectsLine(outer,List.of(List.of(-2,2),List.of(12,2))):"crosses with no endpoints inside";
        assert !Geo.intersectsLine(outer,List.of(List.of(4,4),List.of(6,6))):"line entirely inside hole";
        assert Geo.intersectsPolygons(outer,polygon(2,2,4,4));
        assert !Geo.intersectsPolygons(outer,polygon(4,4,6,6)):"polygon inside hole";
        assert !Geo.intersectsPolygons(outer,polygon(11,11,12,12));
        assert Geo.intersectsPolygons(outer,polygon(9,9,11,11));

        Map<String,Object> zone=polygon(49.07,55.82,49.09,55.84);
        String osm="""
            {"elements":[
              {"type":"way","id":101,"tags":{"highway":"residential","name":"Тверская улица","name:tt":"Тверская урамы"},"geometry":[{"lon":49.06,"lat":55.83},{"lon":49.10,"lat":55.83}]},
              {"type":"way","id":102,"tags":{"highway":"residential","name":"Мира улица"},"geometry":[{"lon":49.07,"lat":55.825},{"lon":49.08,"lat":55.825}]},
              {"type":"way","id":103,"tags":{"highway":"residential","name":"Посторонняя"},"geometry":[{"lon":49.04,"lat":55.80},{"lon":49.05,"lat":55.80}]}
            ]}
            """;
        assert OverpassStreets.query(zone).contains("way[\"highway\"][\"name\"]");
        List<Map<String,Object>> osmWays=OverpassStreets.parse(zone,osm);
        assert osmWays.size()==2:osmWays;
        assert Json.array(osmWays.get(0).get("aliases")).contains("Тверская урамы");

        Path dir=Files.createTempDirectory("ucat-geodictionary-");
        try {
            Store store=new Store(dir);
            Map<String,Object> newArea=store.area(Json.obj("name","Микрорайон","geometry",zone,"keywords",""));
            String id=Json.string(newArea.get("id"));
            store.updateStreets(id,osmWays);
            Map<String,Object> parcels=store.importParcels(id,Json.obj("source","Fixture GeoJSON", "type","FeatureCollection",
                "features",List.of(
                    Json.obj("type","Feature","properties",Json.obj("cad_num","16:50:010101:123"),
                        "geometry",polygon(49.071,55.821,49.074,55.824)),
                    Json.obj("type","Feature","properties",Json.obj("cad_num","16:50:010101:124"),
                        "geometry",polygon(49.12,55.85,49.13,55.86)))));
            assert ((Number)parcels.get("accepted")).intValue()==1;
            assert ((Number)parcels.get("skipped")).intValue()==1;
            store.importParcels(id,Json.obj("parcels",List.of(Json.obj("cadastralNumber","16:50:050101:99"))));
            assert ((Number)store.areas().get(0).get("streetCount")).intValue()==2;
            assert ((Number)store.areas().get(0).get("parcelCount")).intValue()==2;
            assert !store.areas().get(0).containsKey("streets"):"API must omit oversized street data";

            Map<String,Object> a=store.event(Json.obj("title","Проект реконструкции на улице Тверской",
                "description","Исполнитель рассматривает участок 16:50:010101:123"));
            Map<String,Object> hit=store.view().stream().filter(x->x.get("id").equals(a.get("id"))).findFirst().orElseThrow();
            assert "HINT".equals(hit.get("relevance"));
            List<Object> ev=Json.array(hit.get("geoEvidence"));
            assert ev.stream().anyMatch(x->"CADASTRAL".equals(Json.object(x).get("kind")));
            assert ev.stream().anyMatch(x->"STREET".equals(Json.object(x).get("kind"))):ev;
            assert ev.stream().anyMatch(x->"IMPORTED_GEOMETRY".equals(Json.object(x).get("certainty")));
            store.event(Json.obj("title","О победы в мировом первенстве","description","Никаких улиц здесь нет"));
            assert "UNLOCATED".equals(store.view().get(0).get("relevance"));
            store.event(Json.obj("title","Ремонт ул. Мира"));
            assert "HINT".equals(store.view().get(0).get("relevance"));
            store.event(Json.obj("title","Кадастровый номер: 16:50:050101:99"));
            assert Json.array(store.view().get(0).get("geoEvidence")).stream().anyMatch(x->
                "AREA_DECLARED".equals(Json.object(x).get("certainty")));
            store.event(Json.obj("title","Другой кадастровый номер: 16:50:010101:12"));
            assert "UNLOCATED".equals(store.view().get(0).get("relevance")) : "No partial cadastral matches";
            Map<String,Object> linked=store.event(Json.obj("title","Закупка реконструкции Тверской улицы",
                "description","Земельный участок 16:50:010101:123", "source","EIS_ROADS","stage","PROCUREMENT"));
            List<Map<String,Object>> related=ProjectLinks.candidates(Json.string(a.get("id")),store.view());
            assert related.stream().anyMatch(x->linked.get("id").equals(x.get("eventId"))
                && "SHARED_PARCEL_AND_STREET".equals(x.get("relation"))):related;
            assert related.stream().allMatch(x->"REVIEW".equals(x.get("status")));
            Store reloaded=new Store(dir);
            assert ((Number)reloaded.areas().get(0).get("parcelCount")).intValue()==2;
            assert "HINT".equals(reloaded.view().stream().filter(x->x.get("id").equals(a.get("id")))
                .findFirst().orElseThrow().get("relevance"));
        }finally {
            Files.deleteIfExists(dir.resolve("activist.json"));Files.deleteIfExists(dir);
        }
        System.out.println("GeoEnrichmentTest: all assertions passed.");
    }
}
