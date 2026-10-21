package ru.activisthelp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** On-demand OSM street names for one small watch polygon. Never queries OSM on every document. */
public final class OverpassStreets {
    private static final URI ENDPOINT=URI.create("https://overpass-api.de/api/interpreter");
    private static final int MAX_RESPONSE=6_000_000;
    private static final int MAX_WAYS=1800;
    private static final int MAX_CONTEXT_WAYS=2600;
    public record Network(List<Map<String,Object>> streets,List<Map<String,Object>> contextWays) {}
    private OverpassStreets() {}

    /** Named highway ways intersecting the bbox; application performs a separate polygon intersection. */
    public static String query(Map<String,Object> polygon) {
        double[] b=Geo.bounds(polygon);
        if(b[2]-b[0]>0.055 || b[3]-b[1]>0.055)
            throw new IllegalArgumentException("Для Overpass разделите зону на полигоны менее 0,055° по каждой оси (≈3–6 км)");
        if(b[2]-b[0]<0.000005||b[3]-b[1]<0.000005)
            throw new IllegalArgumentException("Полигон слишком узкий для загрузки улиц");
        return String.format(Locale.ROOT,"[out:json][timeout:25];way[\"highway\"][\"name\"](%.7f,%.7f,%.7f,%.7f);out body geom;",
                b[0],b[1],b[2],b[3]);
    }
    /** Fetches once on explicit user request. Failures are not interpreted as 'there are no streets'. */
    public static List<Map<String,Object>> fetch(Map<String,Object> polygon) throws Exception {
        String q=query(polygon);
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest request=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(35))
                .header("User-Agent","ucat-geo/0.2 (https://github.com/Eljah/ucat)")
                .header("Content-Type","application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("data="+URLEncoder.encode(q,StandardCharsets.UTF_8)))
                .build();
        HttpResponse<byte[]> response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200)throw new IOException("Overpass HTTP "+response.statusCode());
        if(response.body().length>MAX_RESPONSE)throw new IOException("Overpass response exceeds 6MB");
        return parse(polygon,new String(response.body(),StandardCharsets.UTF_8));
    }
    /**
     * Enlarge bbox to recover crossing roads that lie just outside the watch area.
     * Bounded context prevents city-scale download; unresolved endpoints remain review items.
     */
    public static String contextQuery(Map<String,Object> polygon) {
        double[] b=Geo.bounds(polygon);
        if(b[2]-b[0]>0.055 || b[3]-b[1]>0.055)
            throw new IllegalArgumentException("Для Overpass разделите зону на полигоны менее 0,055°");
        double margin=0.004;
        return String.format(Locale.ROOT,
            "[out:json][timeout:25];way[\"highway\"][\"name\"](%.7f,%.7f,%.7f,%.7f);out body geom;",
            b[0]-margin,b[1]-margin,b[2]+margin,b[3]+margin);
    }
    public static Network fetchNetwork(Map<String,Object> polygon) throws Exception {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest request=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(40))
                .header("User-Agent","ucat-geo/0.3 (https://github.com/Eljah/ucat)")
                .header("Content-Type","application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("data="+URLEncoder.encode(contextQuery(polygon),StandardCharsets.UTF_8)))
                .build();
        HttpResponse<byte[]> response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200)throw new IOException("Overpass HTTP "+response.statusCode());
        if(response.body().length>MAX_RESPONSE)throw new IOException("Overpass response exceeds 6 MB; reduce watch polygon");
        return parseNetwork(polygon,new String(response.body(),StandardCharsets.UTF_8));
    }
    /** Keeps OSM IDs and node sequences for graph-based crossing, even beyond the polygon. */
    static Network parseNetwork(Map<String,Object> polygon,String response) {
        Map<String,Object> root=Json.object(Json.parse(response));
        if(root.containsKey("remark"))throw new IllegalArgumentException("Overpass incomplete/error: "+root.get("remark"));
        List<Object> raw=Json.array(root.getOrDefault("elements",List.of()));
        if(raw.size()>25_000)throw new IllegalArgumentException("Too many OSM elements");
        List<Map<String,Object>> zoneStreets=new ArrayList<>(),context=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        for(Object element:raw) {
            Map<String,Object> way=Json.object(element);
            if(!"way".equals(way.get("type")))continue;
            Map<String,Object> tags=Json.object(way.getOrDefault("tags",Map.of()));
            String name=Json.string(tags.get("name")).trim();
            if(name.isBlank()||name.length()>150||Json.string(tags.get("highway")).isBlank())continue;
            List<Object> geometry=Json.array(way.getOrDefault("geometry",List.of()));
            List<Object> nodes=Json.array(way.getOrDefault("nodes",List.of()));
            if(geometry.size()<2||geometry.size()>4000||geometry.size()!=nodes.size())continue;
            List<Object> line=new ArrayList<>();
            for(Object object:geometry) {
                Map<String,Object> point=Json.object(object);
                Object lon=point.get("lon"),lat=point.get("lat");
                if(!(lon instanceof Number)||!(lat instanceof Number))break;
                line.add(List.of(lon,lat));
            }
            if(line.size()!=geometry.size())continue;
            String id=Json.string(way.get("id"));if(!ids.add(id))continue;
            LinkedHashSet<String> aliases=new LinkedHashSet<>();
            for(String key:List.of("alt_name","official_name","name:ru","name:tt","old_name","short_name"))
                for(String alt:Json.string(tags.get(key)).split(";")){
                    alt=alt.trim();if(!alt.isBlank()&&alt.length()<=150&&!alt.equalsIgnoreCase(name))aliases.add(alt);
                }
            Map<String,Object> feature=Json.obj("osmId",id,"name",name,"aliases",new ArrayList<>(aliases),
                "kind",Json.string(tags.get("highway")),"source","OpenStreetMap",
                "nodes",nodes,"geometry",line);
            context.add(feature);
            if(Geo.intersectsLine(polygon,line))zoneStreets.add(Json.obj("osmId",id,"name",name,
                "aliases",new ArrayList<>(aliases),"kind",Json.string(tags.get("highway")),"source","OpenStreetMap"));
            if(context.size()>MAX_CONTEXT_WAYS||zoneStreets.size()>MAX_WAYS)
                throw new IllegalArgumentException("Overpass context too large: reduce polygon");
        }
        return new Network(zoneStreets,context);
    }
    static List<Map<String,Object>> parse(Map<String,Object> polygon,String response) {
        Map<String,Object> root=Json.object(Json.parse(response));
        if(root.containsKey("remark"))throw new IllegalArgumentException("Overpass вернул предупреждение/неполный ответ: "+Json.string(root.get("remark")).substring(0,Math.min(200,Json.string(root.get("remark")).length())));
        List<Object> elements=Json.array(root.get("elements"));
        if(elements.size()>25_000)throw new IllegalArgumentException("Слишком много объектов OSM, уменьшите зону");
        List<Map<String,Object>> streets=new ArrayList<>();Set<String> seenIds=new HashSet<>();
        for(Object raw:elements) {
            Map<String,Object> way=Json.object(raw);
            if(!"way".equals(Json.string(way.get("type"))))continue;
            Map<String,Object> tags=Json.object(way.getOrDefault("tags",Map.of()));
            String name=Json.string(tags.get("name")).trim();
            if(name.isBlank()||name.length()>150||Json.string(tags.get("highway")).isBlank())continue;
            List<Object> geometry=Json.array(way.getOrDefault("geometry",List.of()));
            if(geometry.size()<2||geometry.size()>4000)continue;
            List<Object> line=new ArrayList<>();
            for(Object object:geometry) {
                Map<String,Object> point=Json.object(object);
                Object lon=point.get("lon"),lat=point.get("lat");
                if(!(lon instanceof Number)||!(lat instanceof Number))continue;
                line.add(List.of(lon,lat));
            }
            if(!Geo.intersectsLine(polygon,line))continue;
            String osmId=Json.string(way.get("id"));
            if(!seenIds.add(osmId))continue;
            LinkedHashSet<String> aliases=new LinkedHashSet<>();
            for(String tag:List.of("alt_name","official_name","name:ru","name:tt","old_name","short_name")) {
                for(String alt:Json.string(tags.get(tag)).split(";")){
                    alt=alt.trim();if(!alt.isBlank()&&alt.length()<=150&&!alt.equalsIgnoreCase(name))aliases.add(alt);
                }
            }
            streets.add(Json.obj("osmId",osmId,"name",name,"aliases",new ArrayList<>(aliases),
                    "kind",Json.string(tags.get("highway")),"source","OpenStreetMap"));
            if(streets.size()>MAX_WAYS)throw new IllegalArgumentException("Слишком много улиц в одной зоне; разделите полигон");
        }
        return streets;
    }
}
