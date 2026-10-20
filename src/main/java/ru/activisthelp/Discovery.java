package ru.activisthelp;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/**
 * Experimental read-only HTML link discovery. These are NOT supported provider APIs and selectors can change.
 * The app never claims a complete feed or verified deadlines from HTML.
 */
public final class Discovery {
    private record Provider(String id,String title,String url,String linkPattern,int maxLinks) {}
    private static final List<Provider> PROVIDERS=List.of(
        new Provider("DISPUTE","Общественные обсуждения Казани","https://dispute.kzn.ru/","/disputes/\\d+(?:[/?#]|$)",15),
        new Provider("PRAVO","Правовые акты исполкома","https://pravo.tatarstan.ru/kazan/ispolkom?limit=50","npa_id=\\d+",15),
        new Provider("GRAD","Градостроительство Казани","https://kzn.ru/meriya/ispolnitelnyy-komitet/upravlenie-arkhitektury-i-gradostroitelstva-ispolnitelnogo-komiteta-g-kazani/gradostroitelstvo/","(?:\\.pdf|/gradostroitelstvo/)",12),
        new Provider("DVOR","Новости программы «Наш двор»","https://dvor.tatar/news/","/news/[^/]+/?$",10)
    );
    private final Store store;
    private final Notifier notifier=new Notifier();
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(7)).build();
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"activist-discovery");t.setDaemon(true);return t;
    });
    private final AtomicBoolean busy=new AtomicBoolean(false);
    private final ConcurrentHashMap<String,Map<String,Object>> statuses=new ConcurrentHashMap<>();
    private static final Pattern ANCHORS=Pattern.compile("<a\\b[^>]*?href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern META_TITLE=Pattern.compile("<meta\\s+[^>]*?(?:property|name)\\s*=\\s*['\"](?:og:title|twitter:title)['\"][^>]*?content\\s*=\\s*['\"](.*?)['\"]",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern HEADING=Pattern.compile("<h[12][^>]*>(.*?)</h[12]>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern TITLE=Pattern.compile("<title[^>]*>(.*?)</title>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern TAGS=Pattern.compile("<[^>]+>");
    private static final Pattern SCRIPTS=Pattern.compile("<(script|style|noscript)\\b[^>]*>.*?</\\1\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern HTML_ENT=Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);");

    public Discovery(Store store) {
        this.store=store;
        for(Provider p:PROVIDERS)statuses.put(p.id(),state(p,"Не проверялся",0,""));
        for(OfficialSources.Site site:OfficialSources.SITES)statuses.put(site.id(),state(site,"Не проверялся",0,""));
    }
    public void schedule(int hours) {
        executor.scheduleWithFixedDelay(this::scan,120,Math.max(1,hours)*3600L,TimeUnit.SECONDS);
    }
    public boolean scan() {
        if(!busy.compareAndSet(false,true))return false;
        executor.execute(()->{try {runScan();}finally {busy.set(false);}});
        return true;
    }
    public boolean busy(){return busy.get();}
    public List<Map<String,Object>> status(){
        List<Map<String,Object>> data=new ArrayList<>();
        for(Provider p:PROVIDERS)data.add(new LinkedHashMap<>(statuses.get(p.id())));
        for(OfficialSources.Site site:OfficialSources.SITES)data.add(new LinkedHashMap<>(statuses.get(site.id())));
        return data;
    }
    private void runScan() {
        List<Map<String,Object>> discovered=new ArrayList<>();
        for(Provider p:PROVIDERS) {
            int added=0;
            statuses.put(p.id(),state(p,"Проверяется",0,""));
            try {
                String html=fetch(URI.create(p.url()));
                List<Link> links=links(p,html);
                if(links.isEmpty()) {
                    statuses.put(p.id(),state(p,"Разметка не подтверждена",0,
                        "Не найдены ссылки ожидаемого вида. Возможно, сайт изменился, использует JavaScript или ограничивает запросы."));
                    continue;
                }
                for(Link link:links) {
                    if(added>=p.maxLinks())break;
                    String title=link.label();
                    String description="Заголовок получен из публичной HTML-страницы; дата публикации, геометрия и полный текст не проверены.";
                    // "Подробнее" / "Скачать" link labels must be expanded before filtering.
                    if(title.length()<35||title.toLowerCase(Locale.ROOT).contains("подробнее")||title.equalsIgnoreCase("скачать")) {
                        try {
                            sleep(1100);
                            String detail=fetch(URI.create(link.url()));
                            title=extractTitle(detail,p.id());
                        } catch(Exception ignored) {continue;}
                    }
                    if(title.isBlank() || !Classification.relevant(title))continue;
                    String normalized=normalize(title);
                    if(normalized.length()>700)normalized=normalized.substring(0,700);
                    int before=store.events().size();
                    store.event(Json.obj("title",normalized,"url",link.url(),"source",p.id(),"description",description));
                    if(store.events().size()>before){added++;discovered.add(store.view().get(0));}
                    sleep(1000);
                }
                statuses.put(p.id(),state(p,"Проверено (экспериментально)",added,
                    "Найдено ссылок: "+links.size()+". Полнота выдачи и корректность извлечения не гарантированы."));
            }catch(Exception ex) {
                statuses.put(p.id(),state(p,"Ошибка доступа",added,ex.getClass().getSimpleName()+": "+safeMessage(ex)));
            }
        }
        for(OfficialSources.Site site:OfficialSources.SITES) {
            int added=0;
            statuses.put(site.id(),state(site,"Проверяется",0,""));
            try {
                List<Map<String,Object>> candidates;
                String note;
                if("EIS_INBOX".equals(site.id())) {
                    EisInbox.Result res=EisInbox.scan(System.getenv("ACTIVIST_EIS_INBOX"));
                    candidates=res.events();note=res.note();
                } else if("EIS_SOAP".equals(site.id())) {
                    EisSoapClient.Result res=EisSoapClient.scanFromEnvironment();
                    candidates=res.events();note=res.note();
                } else if("LOCAL_PDF_ANNEX".equals(site.id())) {
                    RoadAnnex.Result res=RoadAnnex.scanLocal(System.getenv("ACTIVIST_ANNEX_INBOX"));
                    candidates=res.events();note=res.note();
                } else if(site.id().endsWith("PDF_ANNEX")||"RT_PDF_PLAN".equals(site.id())) {
                    RoadAnnex.Result res=RoadAnnex.scanUrl(site.url(),url->fetch(URI.create(url)),RoadAnnex::fetchPdf);
                    candidates=res.events();note=res.note();
                } else {
                    OfficialSources.Result res=OfficialSources.collect(site.id(),url->fetch(URI.create(url)));
                    candidates=res.events();note=res.note();
                }
                for(Map<String,Object> candidate:candidates) {
                    if(added>=90)break;
                    int before=store.events().size();
                    store.event(candidate);
                    if(store.events().size()>before){added++;discovered.add(store.view().get(0));}
                }
                String status=candidates.isEmpty() && !"EIS_INBOX".equals(site.id())?"Проверено, HTML не подтвержден":"Проверено (экспериментально)";
                if("EIS_INBOX".equals(site.id()) && System.getenv("ACTIVIST_EIS_INBOX")==null)status="Ожидает настройки";
                if("EIS_SOAP".equals(site.id())) {
                    if(System.getenv("ACTIVIST_EIS_TOKEN")==null)status="Ожидает регистрации ЕИС";
                    else if(note.contains("ошибка ")||note.contains("noData/нет archiveUrl"))
                        status="ЕИС: частичный результат / проверьте параметры";
                    else status="ЕИС: запрос выполнен (неполная выборка)";
                }
                if("LOCAL_PDF_ANNEX".equals(site.id()) && System.getenv("ACTIVIST_ANNEX_INBOX")==null)status="Ожидает настройки";
                statuses.put(site.id(),state(site,status,added,note));
            }catch(Exception ex) {
                statuses.put(site.id(),state(site,"Ошибка доступа / разбора",added,ex.getClass().getSimpleName()+": "+safeMessage(ex)));
            }
        }
        notifier.notifyBatch(discovered);
    }
    private static Map<String,Object> state(OfficialSources.Site p,String state,int count,String message) {
        return Json.obj("id",p.id(),"title",p.title(),"url",p.url(),"state",state,"added",count,
            "message",message,"checkedAt",state.equals("Не проверялся")?"":Instant.now().toString());
    }
    private static void sleep(long millis) throws InterruptedException {Thread.sleep(millis);}
    private static String safeMessage(Exception ex) {String s=ex.getMessage();return s==null?"Unexpected error":s.substring(0,Math.min(s.length(),180));}
    private static Map<String,Object> state(Provider p,String state,int count,String message) {
        return Json.obj("id",p.id(),"title",p.title(),"url",p.url(),"state",state,"added",count,
            "message",message,"checkedAt",state.equals("Не проверялся")?"":Instant.now().toString());
    }
    private String fetch(URI uri) throws Exception {
        if(!"https".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("Only HTTPS supported");
        HttpRequest req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("User-Agent","PomoshAktivistu-Prototype/0.1 (read-only private monitoring)")
            .header("Accept","text/html;charset=UTF-8")
            .GET().build();
        HttpResponse<InputStream> res=client.send(req,HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream stream=res.body()) {
            if(res.statusCode()!=200)throw new IllegalStateException("HTTP "+res.statusCode());
            byte[] data=stream.readNBytes(2_000_001);
            if(data.length>2_000_000)throw new IllegalStateException("Document too large");
            return new String(data,StandardCharsets.UTF_8);
        }
    }
    private record Link(String url,String label) {}
    private List<Link> links(Provider p,String html) {
        List<Link> ret=new ArrayList<>();Set<String> unique=new HashSet<>();
        Matcher matcher=ANCHORS.matcher(html);
        Pattern want=Pattern.compile(p.linkPattern(),Pattern.CASE_INSENSITIVE);
        URI root=URI.create(p.url());
        while(matcher.find()) {
            String href=unescape(matcher.group(2)).trim();
            URI target;
            try {target=root.resolve(href).normalize();}catch(Exception ex){continue;}
            if(!"https".equalsIgnoreCase(target.getScheme()))continue;
            if(!Objects.equals(root.getHost(),target.getHost()))continue;
            String uri=target.toString();
            if(uri.equals(p.url())||!want.matcher(uri).find()||!unique.add(uri))continue;
            String label=normalize(matcher.group(3));
            ret.add(new Link(uri,label));
            if(ret.size()>=p.maxLinks())break;
        }
        return ret;
    }
    private static String extractTitle(String html,String source) {
        String title="";
        // Dedicated discussion descriptions may be h2/h3, not the generic website h1.
        if("DISPUTE".equals(source)) {
            Matcher sections=Pattern.compile("<(?:h[1-4]|p|div)[^>]*>(.{0,650}?(?:Общественн(?:ые|ых) обсужден(?:ия|ий)|Оповещение о начале)[^<]{15,500})</",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html);
            if(sections.find())title=normalize(sections.group(1));
        }
        if(title.length()<20) {
            Matcher m=HEADING.matcher(html);
            while(m.find()) {String attempt=normalize(m.group(1));if(attempt.length()>25 && Classification.relevant(attempt)){title=attempt;break;}}
        }
        if(title.isBlank()) {Matcher m=META_TITLE.matcher(html);if(m.find())title=normalize(m.group(1));}
        if(title.isBlank()) {Matcher m=TITLE.matcher(html);if(m.find())title=normalize(m.group(1));}
        return title;
    }
    private static String normalize(String html) {
        String raw=SCRIPTS.matcher(html).replaceAll(" ");
        raw=TAGS.matcher(raw).replaceAll(" ");
        return unescape(raw).replace('\u00a0',' ').replaceAll("\\s+"," ").trim();
    }
    private static String unescape(String str) {
        String s=str.replace("&nbsp;"," ").replace("&quot;","\"").replace("&lt;","<").replace("&gt;",">")
            .replace("&amp;","&").replace("&#39;","'");
        Matcher m=HTML_ENT.matcher(s);StringBuffer result=new StringBuffer();
        while(m.find()) {
            try {String n=m.group(1);int cp=n.startsWith("x")?Integer.parseInt(n.substring(1),16):Integer.parseInt(n);
                m.appendReplacement(result,Matcher.quoteReplacement(new String(Character.toChars(cp))));
            }catch(Exception e){m.appendReplacement(result,Matcher.quoteReplacement(m.group()));}
        }
        m.appendTail(result);return result.toString();
    }
}
