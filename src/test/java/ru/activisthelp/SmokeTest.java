package ru.activisthelp;

import java.nio.file.*;
import java.util.*;

/** Offline smoke tests without JUnit. Run with java -ea ru.activisthelp.SmokeTest. */
public final class SmokeTest {
    public static void main(String[] args)throws Exception {
        Object source=Json.parse("{\"a\":[1,2,{\"b\":\"Привет \\u263a\"}],\"valid\":true}");
        Map<String,Object> parsed=Json.object(Json.parse(Json.stringify(source)));
        assert Boolean.TRUE.equals(parsed.get("valid"));
        assert Json.array(parsed.get("a")).size()==3;
        assert Json.string(Json.object(Json.array(parsed.get("a")).get(2)).get("b")).equals("Привет ☺");
        Map<String,Object> g=Json.obj("type","Polygon","coordinates",List.of(
            List.of(List.of(0,0),List.of(10,0),List.of(10,10),List.of(0,10),List.of(0,0)),
            List.of(List.of(3,3),List.of(7,3),List.of(7,7),List.of(3,7),List.of(3,3))));
        Geo.validate(g);
        assert Geo.contains(g,1,1);
        assert !Geo.contains(g,5,5):"hole must be excluded";
        assert !Geo.contains(g,11,5);
        assert Classification.stage("О подготовке проекта межевания территории").equals("PREPARATION");
        assert Classification.stage("Оповещение о начале общественных обсуждений").equals("DISCUSSION");
        assert Classification.stage("Об утверждении проекта планировки").equals("APPROVAL");
        Path dir=Files.createTempDirectory("activist-smoke-");
        try {
            Store store=new Store(dir);
            store.area(Json.obj("name","Тестовый квартал","keywords","межевание;Заречье","geometry",g));
            Map<String,Object> ev=store.event(Json.obj("title","О подготовке проекта межевания территории", "url","https://test.invalid/123"));
            assert store.events().size()==1;
            assert store.view().get(0).get("relevance").equals("HINT");
            store.event(Json.obj("title","Повтор", "url","https://test.invalid/123"));
            assert store.events().size()==1;
            store.updateEvent(Json.string(ev.get("id")),Json.obj("position",List.of(1,1),"seen",true,"note","Запросить план"));
            assert store.view().get(0).get("relevance").equals("MATCH");
            assert Boolean.TRUE.equals(store.events().get(0).get("seen"));
            Store reloaded=new Store(dir);
            assert reloaded.areas().size()==1 && reloaded.events().size()==1;
            assert reloaded.view().get(0).get("relevance").equals("MATCH");
            reloaded.updateEvent(Json.string(ev.get("id")),Json.obj("position",List.of(13,2)));
            assert reloaded.view().get(0).get("relevance").equals("OUTSIDE");
            reloaded.removeArea(Json.string(reloaded.areas().get(0).get("id")));
            assert reloaded.areas().isEmpty();
        }finally {
            Files.deleteIfExists(dir.resolve("activist.json"));
            Files.deleteIfExists(dir);
        }
        System.out.println("SmokeTest: all assertions passed.");
    }
}
