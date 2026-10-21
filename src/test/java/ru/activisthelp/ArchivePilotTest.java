package ru.activisthelp;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Deterministic replay of real archived act TITLES, without claiming to have live OSM or PDF geometry. */
public final class ArchivePilotTest {
    public static void main(String[] args) throws Exception {
        Map<String,Object> replay=Json.object(Json.parse(Files.readString(
            Path.of("examples/kazan-road-archive-pilot.json"),StandardCharsets.UTF_8)));
        Path temp=Files.createTempDirectory("ucat-kazan-archive-");
        try {
            Store store=new Store(temp);
            store.area(Json.obj("name","Pilot Nesmelova / Kirov dam — approximate only",
                "keywords","Несмелова;Кировская дамба",
                "geometry",Json.obj("type","Polygon","coordinates",List.of(List.of(
                    List.of(49.057,55.822),List.of(49.092,55.822),
                    List.of(49.092,55.837),List.of(49.057,55.837),List.of(49.057,55.822))))));
            for(Object doc:Json.array(replay.get("events")))store.event(Json.object(doc));
            Map<String,String> statuses=new LinkedHashMap<>();Map<String,String> types=new LinkedHashMap<>();
            for(var e:store.view()) {
                statuses.put(Json.string(e.get("externalId")),Json.string(e.get("relevance")));
                Map<String,Object> span=e.get("linearWork") instanceof Map<?,?> ? Json.object(e.get("linearWork")):Map.of();
                types.put(Json.string(e.get("externalId")),Json.string(span.get("type")));
            }
            Map<String,String> expected=Map.of(
                "KZN-2020-1331","HINT", "KZN-2020-1726","HINT",
                "KZN-2020-2260","UNLOCATED", "KZN-2022-3763","UNLOCATED",
                "KZN-2025-1583","HINT", "KZN-2026-1708","UNLOCATED");
            if(!expected.equals(statuses))throw new AssertionError("False-positive geonames / stage leak: "+statuses);
            if(!"CROSS_STREETS".equals(types.get("KZN-2026-1708")))
                throw new AssertionError("Named section Batyrshina not recognized: "+types);
            System.out.println("ArchivePilotTest: six real published title fixtures, expected review status and no stage leakage passed.");
        } finally {
            Files.deleteIfExists(temp.resolve("activist.json"));Files.deleteIfExists(temp);
        }
    }
}
