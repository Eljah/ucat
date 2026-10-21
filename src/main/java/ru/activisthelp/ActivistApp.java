package ru.activisthelp;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * "Помощь активисту" single-user local proof of concept.
 * Java 17 JDK HttpServer; no extra libraries, no accounts, no external server-side database.
 */
public final class ActivistApp {
    private final Store store;
    private final Discovery discovery;
    private final HttpServer server;
    public ActivistApp(int port,Path dataDir) throws Exception {
        store=new Store(dataDir);
        if(!"false".equalsIgnoreCase(System.getenv("ACTIVIST_DEMO")))store.seed();
        discovery=new Discovery(store);
        discovery.schedule(Integer.parseInt(System.getenv().getOrDefault("ACTIVIST_SCAN_HOURS","6")));
        // Bind only to loopback by default (single-user prototype, no auth).
        String bind=System.getenv().getOrDefault("ACTIVIST_BIND","127.0.0.1");
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind),port),0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public void stop(){server.stop(0);}
    private void handle(HttpExchange ex) throws IOException {
        try {
            String path=ex.getRequestURI().getPath(),method=ex.getRequestMethod();
            if(path.equals("/")) {resource(ex,"/web/index.html","text/html; charset=utf-8");return;}
            if(path.equals("/app.css")){resource(ex,"/web/app.css","text/css; charset=utf-8");return;}
            if(path.equals("/app.js")){resource(ex,"/web/app.js","text/javascript; charset=utf-8");return;}
            if(path.equals("/api/health")&&method.equals("GET")) {reply(ex,200,Json.obj("ok",true,"now",Instant.now().toString()));return;}
            if(path.equals("/api/areas")) {
                if(method.equals("GET"))reply(ex,200,store.areas());
                else if(method.equals("POST"))reply(ex,201,store.area(body(ex)));
                else methodNotAllowed(ex);return;
            }
            if(path.startsWith("/api/areas/")&&path.endsWith("/streets")&&method.equals("POST")) {
                String id=path.substring("/api/areas/".length(),path.length()-"/streets".length());
                Map<String,Object> area=store.areaRecord(id);
                if(area==null) {reply(ex,404,Json.obj("error","Watch area not found"));return;}
                try {
                    OverpassStreets.Network network=OverpassStreets.fetchNetwork(Json.object(area.get("geometry")));
                    List<Map<String,Object>> ways=network.streets();
                    if(ways.isEmpty()&&!Json.array(area.getOrDefault("streets",List.of())).isEmpty()) {
                        reply(ex,502,Json.obj("error","Overpass не нашёл улиц: сохранён предыдущий справочник, проверьте вручную"));return;
                    }
                    reply(ex,200,Json.obj("area",store.updateStreetNetwork(id,ways,network.contextWays()),
                        "ways",ways.size(),"contextWays",network.contextWays().size(),
                        "note","Загружены дороги внутри полигона и ближайшая сеть для проверки отрезков. OSM не подтверждает границы проектируемых работ."));
                }catch(IllegalArgumentException err) {throw err;}
                catch(Exception err) {reply(ex,502,Json.obj("error","Overpass недоступен: "+err.getClass().getSimpleName()));}
                return;
            }
            if(path.startsWith("/api/areas/")&&path.endsWith("/parcels")&&method.equals("POST")) {
                String id=path.substring("/api/areas/".length(),path.length()-"/parcels".length());
                Map<String,Object> info=store.importParcels(id,body(ex));
                reply(ex,info==null?404:200,info==null?Json.obj("error","Watch area not found"):info);
                return;
            }
            if(path.startsWith("/api/areas/")&&path.endsWith("/dictionary")&&method.equals("GET")) {
                String id=path.substring("/api/areas/".length(),path.length()-"/dictionary".length());
                Map<String,Object> info=store.areaDictionary(id);
                reply(ex,info==null?404:200,info==null?Json.obj("error","Watch area not found"):info);
                return;
            }
            if(path.startsWith("/api/areas/")&&method.equals("DELETE")) {
                boolean removed=store.removeArea(path.substring("/api/areas/".length()));
                reply(ex,removed?200:404,Json.obj("deleted",removed));return;
            }
            if(path.equals("/api/projects")) {
                if(method.equals("GET"))reply(ex,200,store.projects());
                else if(method.equals("POST"))reply(ex,201,store.createProject(body(ex)));
                else methodNotAllowed(ex);return;
            }
            if(path.startsWith("/api/projects/")) {
                String suffix=path.substring("/api/projects/".length());
                if(suffix.endsWith("/events")) {
                    String projectId=suffix.substring(0,suffix.length()-"/events".length());
                    if(!method.equals("POST")&&!method.equals("DELETE")) {methodNotAllowed(ex);return;}
                    String eventId=Json.string(body(ex).get("eventId"));
                    Map<String,Object> result=store.linkProject(projectId,eventId,method.equals("POST"));
                    reply(ex,result==null?404:200,result==null?Json.obj("error","Project not found"):result);return;
                }
                if(method.equals("GET")){
                    Map<String,Object> project=store.project(suffix);
                    reply(ex,project==null?404:200,project==null?Json.obj("error","Project not found"):project);
                }else methodNotAllowed(ex);return;
            }
            if(path.equals("/api/events")) {
                if(method.equals("GET"))reply(ex,200,store.view());
                else if(method.equals("POST"))reply(ex,201,store.event(body(ex)));
                else methodNotAllowed(ex);return;
            }
            if(path.startsWith("/api/events/")&&method.equals("PATCH")) {
                Map<String,Object> changed=store.updateEvent(path.substring("/api/events/".length()),body(ex));
                reply(ex,changed==null?404:200,changed==null?Json.obj("error","Not found"):changed);return;
            }
            if(path.equals("/api/import")&&method.equals("POST")) {
                List<Object> batch=Json.array(body(ex).get("events"));
                if(batch.size()>200)throw new IllegalArgumentException("Import at most 200 events at a time");
                int added=0;for(Object item:batch) {
                    int before=store.events().size();store.event(Json.object(item));
                    if(store.events().size()>before)added++;
                }
                reply(ex,200,Json.obj("added",added,"duplicates",batch.size()-added));return;
            }
            if(path.equals("/api/related")&&method.equals("GET")) {
                String query=ex.getRequestURI().getRawQuery();
                String id=query!=null&&query.startsWith("eventId=")?query.substring("eventId=".length()):"";
                if(!id.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("Provide eventId UUID");
                reply(ex,200,Json.obj("eventId",id,"candidates",ProjectLinks.candidates(id,store.view())));return;
            }
            if(path.equals("/api/sources")&&method.equals("GET")) {reply(ex,200,Json.obj("busy",discovery.busy(),"sources",discovery.status()));return;}
            if(path.equals("/api/scan")&&method.equals("POST")) {
                reply(ex,202,Json.obj("queued",discovery.scan(),"message","Scan runs on server; check /api/sources."));return;
            }
            if(path.equals("/api/export")&&method.equals("GET")) {
                reply(ex,200,Json.obj("exportedAt",Instant.now().toString(),"areas",store.areaRecords(),"events",store.events()));return;
            }
            if(path.startsWith("/api/")) {reply(ex,404,Json.obj("error","Endpoint not found"));return;}
            reply(ex,404,Json.obj("error","Not found"));
        }catch(IllegalArgumentException ex2){reply(ex,400,Json.obj("error",ex2.getMessage()));}
        catch(Exception ex2){
            ex2.printStackTrace(System.err);
            reply(ex,500,Json.obj("error","Internal error; consult server log"));
        }finally {ex.close();}
    }
    private static Map<String,Object> body(HttpExchange ex) throws IOException {
        try(InputStream input=ex.getRequestBody()) {
            byte[] data=input.readNBytes(250_001);
            if(data.length>250_000)throw new IllegalArgumentException("Request too large");
            return Json.object(Json.parse(new String(data,StandardCharsets.UTF_8)));
        }
    }
    private static void resource(HttpExchange ex,String name,String contentType) throws IOException {
        if(!ex.getRequestMethod().equals("GET")){methodNotAllowed(ex);return;}
        try(InputStream in=ActivistApp.class.getResourceAsStream(name)) {
            if(in==null){reply(ex,404,Json.obj("error","Resource missing"));return;}
            bytes(ex,200,contentType,in.readAllBytes());
        }
    }
    private static void reply(HttpExchange ex,int status,Object object)throws IOException {
        bytes(ex,status,"application/json; charset=utf-8",Json.stringify(object).getBytes(StandardCharsets.UTF_8));
    }
    private static void methodNotAllowed(HttpExchange ex)throws IOException {reply(ex,405,Json.obj("error","Method not allowed"));}
    private static void bytes(HttpExchange ex,int status,String type,byte[] bytes)throws IOException {
        ex.getResponseHeaders().set("Content-Type",type);
        ex.getResponseHeaders().set("Cache-Control","no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy","default-src 'self' https://unpkg.com https://tile.openstreetmap.org; img-src 'self' data: https://tile.openstreetmap.org https://*.tile.openstreetmap.org https://unpkg.com; script-src 'self' https://unpkg.com; style-src 'self' 'unsafe-inline' https://unpkg.com; connect-src 'self' https://tile.openstreetmap.org https://*.tile.openstreetmap.org; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        ex.sendResponseHeaders(status,bytes.length);
        try(OutputStream out=ex.getResponseBody()){out.write(bytes);}
    }
    public static void main(String[] args)throws Exception {
        int port=Integer.parseInt(System.getenv().getOrDefault("ACTIVIST_PORT","8080"));
        Path dir=Path.of(System.getenv().getOrDefault("ACTIVIST_DATA_DIR","./data"));
        ActivistApp app=new ActivistApp(port,dir);
        app.start();
        System.out.println("Помощь активисту: http://127.0.0.1:"+port);
        System.out.println("Локальные данные: "+dir.toAbsolutePath());
    }
}
