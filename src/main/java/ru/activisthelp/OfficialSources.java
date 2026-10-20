package ru.activisthelp;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/**
 * Read-only adapters for municipal acts, road tenders and the Republic's road ministry.
 * All parsers are deliberately conservative: without site access or sufficiently
 * identifiable source text they return no events instead of invented data.
 * Site HTML is not an API; fixture tests verify only the parser, not provider uptime.
 */
public final class OfficialSources {
    private OfficialSources() {}
    @FunctionalInterface interface Downloader { String get(String url) throws Exception; }
    record Site(String id,String title,String url) {}
    record Result(List<Map<String,Object>> events,String note) {}
    record Anchor(String url,String label,String context) {}

    static final String KZN_ACTS_URL="https://kzn.ru/nayti-dokument/munitsipalnye-pravovye-akty-i-inaya-ofitsialnaya-informatsiya/";
    static final String KZN_NEWS_URL="https://kzn.ru/meriya/press-tsentr/novosti/";
    static final String DOCSKZN_URL="https://docskzn.ru/";
    static final String RT_ROADPLAN_URL="https://mindortrans.tatarstan.ru/postanovleniya-kabineta-ministrov-respubliki-36962.htm";
    static final String RT_ORDERS_URL="https://mindortrans.tatarstan.ru/documents/normativ_mintrans_rt.htm";
    static final String RT_NEWS_URL="https://mindortrans.tatarstan.ru/";
    static final String EIS_URL="https://zakupki.gov.ru/epz/order/extendedsearch/results.html";
    static final List<Site> SITES=List.of(
        new Site("KZN_ACTS","Муниципальные правовые акты Казани",KZN_ACTS_URL),
        new Site("KZN_BULLETINS","Анонсы выпусков муниципальных актов",KZN_NEWS_URL),
        new Site("DOCSKZN","Сетевое издание муниципальных актов",DOCSKZN_URL),
        new Site("RT_ROADPLAN","Постановления КМ РТ о дорогах",RT_ROADPLAN_URL),
        new Site("RT_ORDERS","Документы Минтранса Татарстана",RT_ORDERS_URL),
        new Site("RT_ROAD_NEWS","Сообщения Минтранса РТ",RT_NEWS_URL),
        new Site("EIS_ROADS","ЕИС: дорожные закупки в Казани",EIS_URL),
        new Site("EIS_INBOX","ЕИС: локальный экспорт XML/CSV",EIS_URL),
        new Site("EIS_SOAP","ЕИС: официальный SOAP getDocsIP (44-ФЗ)",EisSoapClient.ENDPOINT),
        new Site("KZN_PDF_ANNEX","PDF приложений правовых актов Казани",RoadAnnex.KZN),
        new Site("RT_PDF_ANNEX","PDF приложений постановлений Кабмина РТ",RoadAnnex.RT),
        new Site("RT_PDF_PLAN","Программа дорожных работ РТ: PDF-приложения",RoadAnnex.PLAN),
        new Site("LOCAL_PDF_ANNEX","Локальный импорт PDF-приложений",RoadAnnex.KZN)
    );
    private static final Pattern ANCHOR=Pattern.compile("<a\\b[^>]*?href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern PARAGRAPH=Pattern.compile("<p\\b[^>]*>(.*?)</p\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern TAGS=Pattern.compile("<[^>]+>");
    private static final Pattern NONCONTENT=Pattern.compile("<(script|style|noscript)\\b[^>]*>.*?</\\1\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern DECIMAL_ENTITY=Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);",Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE=Pattern.compile("(?<!\\d)([0-3]?\\d)[.\\-/]([01]?\\d)[.\\-/](20\\d\\d)(?!\\d)");
    private static final Pattern NOTICE=Pattern.compile("(?<!\\d)0\\d{16,21}(?!\\d)");
    private static final Pattern ACT=Pattern.compile("(?Uiu)(?:постановлени[ея]|распоряжени[ея]|решени[ея])(?:.{0,160})(?:от\\s+\\d{1,2}\\.\\d{1,2}\\.20\\d\\d)?.{0,70}№\\s*[0-9]+[а-яa-z-]*");
    private static final Pattern ROAD=Pattern.compile("(?Uiu)(?:автомобильн\\w*\\s+дорог|автодорог)|дорожн\\w*\\s+(?:работ|строительств|хозяйств|инфраструктур)|дорожн\\w*\\s+движени|улично.дорожн\\w*\\s+сет|(?:дорог|улиц|проспект|путепровод|развязк|тротуар|мост|пешеходн\\w*\\s+переход).{0,65}(?:ремонт|строительств|проектирован|реконструкц)|(?:ремонт|строительств|проектирован|реконструкц|асфальтирован|содержани).{0,100}(?:дорог|улиц|проспект|путепровод|развязк|тротуар|мост|проезж|дорожн)|(?:инфраструктур\\w*\\s+для\\s+жизни|безопасн\\w*\\s+качественн\\w*\\s+дорог)");
    private static final Pattern CITY=Pattern.compile("(?Uiu)(?:\\bказан[ьи]\\b|г\\.?\\s*казан[ьи]\\b|город[аеи]?\\s+казан[ьи]\\b|казанск\\w*\\s+(?:улиц|агломерац|город|дорог)|\\bKazan\\b)");
    private static final Pattern OTHER_CITY=Pattern.compile("(?Uiu)(?:набережн\\w*\\s+челн|нижнекамск|альметьевск|зеленодольск|елабуг[аи]|буинск|чистопол|лениногорск)");
    private static final Pattern ROAD_ACT=Pattern.compile("(?Uiu)(?:межеван|планиров|земел|сервитут|изъят|строительств|реконструкц|дорожн|автомобильн|тротуар|транспорт|ограничени\\w*\\s+движен|улиц|благоустройств|ливнев)");

    static Result collect(String id,Downloader fetcher) throws Exception {
        return switch(id) {
            case "KZN_ACTS" -> {
                String html=fetcher.get(KZN_ACTS_URL);
                List<Map<String,Object>> found=municipalIndex(KZN_ACTS_URL,html);
                yield new Result(found,"Найдено подходящих актов в доступном HTML: "+found.size()+". Полнота не гарантирована; текст приложений не прочитан.");
            }
            case "KZN_BULLETINS" -> scanBulletins(fetcher);
            case "DOCSKZN" -> {
                List<Map<String,Object>> entries=gazetteIndex(fetcher.get(DOCSKZN_URL));
                yield new Result(entries,"Проверена страница сетевого издания. Только видимые заголовки, не текст PDF-выпусков; отсутствие результатов НЕ означает отсутствия актов.");
            }
            case "RT_ROADPLAN" -> {
                List<Map<String,Object>> found=ministryIndex(RT_ROADPLAN_URL,fetcher.get(RT_ROADPLAN_URL),true);
                yield new Result(found,"Республиканская программа: без просмотра PDF нет доказанной географии объектов. Совпадения требуют проверки приложений.");
            }
            case "RT_ORDERS" -> {
                List<Map<String,Object>> found=ministryIndex(RT_ORDERS_URL,fetcher.get(RT_ORDERS_URL),false);
                yield new Result(found,"Отбор по тематике дорог из видимого реестра; отсутствие записи не означает отсутствие приказа.");
            }
            case "RT_ROAD_NEWS" -> scanMinistryNews(fetcher);
            case "EIS_ROADS" -> scanEis(fetcher);
            default -> throw new IllegalArgumentException("Unsupported source: "+id);
        };
    }

    static List<Anchor> anchors(String base,String html,String expectedHost,int max) {
        List<Anchor> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        Matcher m=ANCHOR.matcher(html);
        while(m.find() && out.size()<max) {
            String link=trusted(base,m.group(2),expectedHost);
            if(link==null||!seen.add(link))continue;
            String label=plain(m.group(3));
            // Context captures text outside PDF/icon anchors (typical ministerial file lists).
            int from=Math.max(0,m.start()-600),to=Math.min(html.length(),m.end()+220);
            out.add(new Anchor(link,label,plain(html.substring(from,to))));
        }
        return out;
    }
    static String trusted(String base,String href,String host) {
        try {
            URI u=URI.create(base).resolve(unescape(href.trim())).normalize();
            if(!"https".equalsIgnoreCase(u.getScheme()) || !host.equalsIgnoreCase(u.getHost()))return null;
            return u.toString();
        }catch(Exception ex){return null;}
    }
    static String plain(String html) {
        String raw=NONCONTENT.matcher(html).replaceAll(" ");
        raw=TAGS.matcher(raw).replaceAll(" ");
        return unescape(raw).replace('\u00a0',' ').replaceAll("\\s+"," ").trim();
    }
    private static String unescape(String raw) {
        String s=raw.replace("&nbsp;"," ").replace("&quot;","\"").replace("&laquo;","«")
            .replace("&raquo;","»").replace("&amp;","&").replace("&lt;","<")
            .replace("&gt;",">").replace("&#39;","'");
        Matcher m=DECIMAL_ENTITY.matcher(s);StringBuffer sb=new StringBuffer();
        while(m.find()) {
            try {int cp=m.group(1).startsWith("x")||m.group(1).startsWith("X")
                ?Integer.parseInt(m.group(1).substring(1),16):Integer.parseInt(m.group(1));
                m.appendReplacement(sb,Matcher.quoteReplacement(new String(Character.toChars(cp))));
            }catch(Exception ex){m.appendReplacement(sb,Matcher.quoteReplacement(m.group()));}
        }
        m.appendTail(sb);return sb.toString();
    }
    private static String published(String text) {
        Matcher m=DATE.matcher(text);if(!m.find())return "";
        try {return java.time.LocalDate.of(Integer.parseInt(m.group(3)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(1))).toString();}
        catch(Exception ex){return "";}
    }
    private static String stable(String text) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ").trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash,0,12);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static Map<String,Object> event(String source,String title,String url,String ext,String description,String published,String scope,String program,String authority,String documentKind) {
        return Json.obj("source",source,"title",title,"url",url,"externalId",ext,
            "description",description,"publishedAt",published,"locationScope",scope,
            "program",program,"authority",authority,"documentKind",documentKind,
            "requiresReview",!"CONFIRMED_GEOMETRY".equals(scope));
    }
    /** Multiple decrees share one bulletin URL; each heading becomes its own externalId. */
    static List<Map<String,Object>> municipalBulletin(String url,String html) {
        List<String> parts=new ArrayList<>(); Matcher m=PARAGRAPH.matcher(html);
        while(m.find()&&parts.size()<800)parts.add(plain(m.group(1)));
        List<Map<String,Object>> out=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i+1<parts.size();i++) {
            String act=parts.get(i),subject=parts.get(i+1);
            if(!ACT.matcher(act).find() || subject.length()<14 || !ROAD_ACT.matcher(subject).find())continue;
            String head=act.length()>280?act.substring(0,280):act;
            String title=head+" — "+(subject.length()>390?subject.substring(0,390):subject);
            if(!Classification.relevant(title))continue;
            String id=stable(act);
            if(!ids.add(id))continue;
            out.add(event("KZN_ACT",title,url,id,
                "Анонс/список муниципальных актов: название в выпуске, не полный текст. Требуется проверить оригинал и приложения.",
                "","UNKNOWN","","Исполком / мэрия Казани","ACT"));
            out.get(out.size()-1).put("issuedAt",published(act));
            out.get(out.size()-1).put("stage",legalStage(subject));
            if(out.size()>=90)break;
        }
        return out;
    }
    static List<Map<String,Object>> municipalIndex(String url,String html) {
        List<Map<String,Object>> found=new ArrayList<>(municipalBulletin(url,html));
        Set<String> ids=new HashSet<>();for(var ev:found)ids.add(Json.string(ev.get("externalId")));
        for(Anchor a:anchors(url,html,"kzn.ru",220)) {
            String title=a.label();
            if(!ROAD_ACT.matcher(title).find()||!Classification.relevant(title))continue;
            if(title.length()<27||title.length()>700)continue;
            // Acts on the current official portal have their own detail links; never create generic navigation events.
            if(!(a.url().contains("nayti-dokument/")||a.url().matches("(?i).*\\.(?:pdf|docx?)(?:\\?.*)?$")))continue;
            String id=ACT.matcher(title).find()?stable(title):stable(a.url());
            if(!ids.add(id))continue;
            found.add(event("KZN_ACT",title,a.url(),id,
                "Найден заголовок акта в муниципальном реестре; приложение и пространственные границы не анализировались.",
                "","UNKNOWN","","Исполком / мэрия Казани","ACT"));
            found.get(found.size()-1).put("issuedAt",published(title));
            found.get(found.size()-1).put("stage",legalStage(title));
            if(found.size()>=100)break;
        }
        return found;
    }
    private static String legalStage(String subject) {
        String s=subject.toLowerCase(Locale.ROOT);
        if(s.matches("(?s).*о\\s+подготовке.*")||s.contains("об изъятии"))return "PREPARATION";
        if(s.contains("об утверждении")||s.contains("о внесении изменений в проект"))return "APPROVAL";
        if(s.contains("об ограничении движения"))return "OTHER";
        return "OTHER"; // A legal act about construction is not evidence that works have started.
    }
    static List<Map<String,Object>> gazetteIndex(String html) {
        List<Map<String,Object>> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Anchor a:anchors(DOCSKZN_URL,html,"docskzn.ru",250)) {
            String title=a.label();
            if(title.length()<35 || !ROAD_ACT.matcher(title).find() || !Classification.relevant(title))continue;
            if(!seen.add(a.url()))continue;
            var rec=event("KZN_ACT",title,a.url(),stable(title),
                "Заголовок опубликован в сетевом муниципальном издании. PDF-приложения, даты публикации и контуры работ не извлечены.",
                "","UNKNOWN","","Муниципальное сетевое издание Казани","ACT");
            rec.put("stage",legalStage(title));out.add(rec);
            if(out.size()>=80)break;
        }
        return out;
    }
    private static Result scanBulletins(Downloader fetcher) throws Exception {
        String index=fetcher.get(KZN_NEWS_URL);
        List<Anchor> matches=new ArrayList<>();
        for(Anchor a:anchors(KZN_NEWS_URL,index,"kzn.ru",450))
            if(a.url().matches("(?i).*novosti/(?:budet-opublikovano-setevoe-izdanie|opublikovany-dokumenty)[^/]+/?(?:\\?.*)?"))matches.add(a);
        List<Map<String,Object>> records=new ArrayList<>();List<String> failed=new ArrayList<>();
        int read=0;
        for(Anchor a:matches) {
            if(read++>=8)break;
            try {records.addAll(municipalBulletin(a.url(),fetcher.get(a.url())));}
            catch(Exception e){failed.add("HTTP/parse: "+a.url());}
        }
        String message="Выпусков просмотрено: "+Math.min(read,8)+", актов по тематике: "+records.size();
        if(!failed.isEmpty())message+="; не прочитано выпусков: "+failed.size();
        if(matches.isEmpty())message+=". На странице нет распознаваемых ссылок на выпуски: разметка могла измениться.";
        return new Result(records,message);
    }
    static List<Map<String,Object>> ministryIndex(String page,String html,boolean roadPlan) {
        List<Map<String,Object>> out=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Anchor a:anchors(page,html,"mindortrans.tatarstan.ru",300)) {
            String title=a.label();
            // PDF anchors often say only "PDF". Pick the nearest preceding document title.
            if(title.length()<27||title.equalsIgnoreCase("pdf")||title.equalsIgnoreCase("docx")) {
                Matcher m=Pattern.compile("(?Uiu)((?:постановлени[ея]|приказ|план|программ[аы]).{15,360}?)(?:PDF|DOCX|$)").matcher(a.context());
                if(m.find())title=m.group(1).trim();
            }
            if(title.length()<30||title.length()>700||!ROAD.matcher(title).find())continue;
            if(roadPlan && !title.toLowerCase(Locale.ROOT).contains("постановлен") && !title.toLowerCase(Locale.ROOT).contains("дорожн"))continue;
            String id=stable(title);
            if(!ids.add(id))continue;
            boolean local=CITY.matcher(title).find();
            String details="Текст заголовка размещён Минтрансом РТ. "+(local
                ?"Упоминание Казани в заголовке НЕ устанавливает точную границу работ."
                :"В заголовке нет географии Казани. Нужна проверка приложений, чтобы установить, входит ли объект в город.");
            out.add(event("RT_MINTRANS",title,a.url(),id,details,"",
                local?"KAZAN_MENTION":"REGIONAL_REVIEW",program(title),"Минтранс РТ / Кабинет министров РТ",roadPlan?"ROAD_PROGRAM":"MINISTRY_ACT"));
            out.get(out.size()-1).put("issuedAt",published(title));
            if(out.size()>=70)break;
        }
        return out;
    }
    private static Result scanMinistryNews(Downloader fetcher) throws Exception {
        String html=fetcher.get(RT_NEWS_URL);List<Map<String,Object>> found=new ArrayList<>();
        for(Anchor a:anchors(RT_NEWS_URL,html,"mindortrans.tatarstan.ru",320)) {
            if(!a.url().matches("(?i).*/news/\\d+\\.htm(?:\\?.*)?"))continue;
            if(!ROAD.matcher(a.label()).find())continue;
            // No city name? A regional announcement is a review candidate, not an exact polygon hit.
            String title=a.label();
            found.add(event("RT_MINTRANS",title,a.url(),stable(a.url()),
                "Сообщение Минтранса РТ. Без проверки полного текста нельзя установить городской участок или этап проекта.",
                "",CITY.matcher(title).find()?"KAZAN_MENTION":"REGIONAL_REVIEW",program(title),"Минтранс РТ","NEWS"));
            if(found.size()>=30)break;
        }
        return new Result(found,"Проверена главная лента новостей Минтранса. Региональные сообщения требуют уточнения по городу и полигонам.");
    }
    private static Result scanEis(Downloader fetcher) throws Exception {
        String[] queries={"строительство автомобильной дороги Казань","реконструкция ремонт улицы Казань","проектирование путепровода Казань"};
        List<Map<String,Object>> result=new ArrayList<>();List<String> failures=new ArrayList<>();int fetched=0;
        for(String term:queries) {
            String link=EIS_URL+"?searchString="+URLEncoder.encode(term,StandardCharsets.UTF_8)+"&pageNumber=1";
            try {result.addAll(eisHtml(link,fetcher.get(link)));fetched++;}
            catch(Exception ex){failures.add(ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage()));}
        }
        if(fetched==0)throw new IllegalStateException("Не удалось получить страницы ЕИС: "+String.join("; ",failures));
        String note="Получено поисковых страниц: "+fetched+"/"+queries.length+"; отобрано подходящих карточек: "+result.size()+".";
        if(!failures.isEmpty())note+=" Частичные ошибки: "+String.join("; ",failures);
        if(result.isEmpty())note+=" Возможно, ЕИС ограничивает HTML-доступ или изменила карточки. Используйте локальный экспорт ЕИС.";
        return new Result(result,note);
    }
    /** Conservative extractor for typical EIS result blocks; never treats buyer's legal address as work location. */
    static List<Map<String,Object>> eisHtml(String page,String html) {
        List<Map<String,Object>> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        // In EIS search output a "search-registry-entry-block" contains one notice.
        String[] blocks=html.split("(?i)(?=<div[^>]{0,180}class=[\"'][^\"']*search-registry-entry-block)");
        for(String block:blocks) {
            for(Anchor a:anchors(page,block,"zakupki.gov.ru",90)) {
                if(!a.url().contains("/epz/order/notice/"))continue;
                String body=plain(block);
                String number=noticeNumber(a.url()+" "+a.label()+" "+body);
                String project=field(body,"Объект закупки","Заказчик","Начальная","Размещено","Тип закупки");
                if(project.isBlank())project=field(body,"Наименование объекта закупки","Заказчик","Начальная","Размещено");
                if(project.isBlank())continue; // do not guess a project from a notice number
                String place=field(body,"Место выполнения работ","Заказчик","Начальная","Дата");
                if(!roadWorks(project)||!candidateKazan(project,place))continue;
                String id=number.isBlank()?stable(a.url()):number;
                if(!seen.add(id))continue;
                out.add(procurement(project,a.url(),id,place,"","","ЕИС (HTML-карточка)",""));
                break;
            }
            if(out.size()>=60)break;
        }
        return out;
    }
    static String field(String text,String start,String... stops) {
        String lower=text.toLowerCase(Locale.ROOT),needle=start.toLowerCase(Locale.ROOT);
        int pos=lower.indexOf(needle);if(pos<0)return "";
        int from=pos+needle.length(),to=Math.min(text.length(),from+600);
        for(String stop:stops) {int i=lower.indexOf(stop.toLowerCase(Locale.ROOT),from);if(i>=0)to=Math.min(to,i);}
        return text.substring(from,to).replaceFirst("^[\\s:—-]+","").trim();
    }
    static boolean roadWorks(String text) {
        String s=text.toLowerCase(Locale.ROOT);
        return ROAD.matcher(s).find() && Pattern.compile("(?Uiu)строительств|ремонт|реконструк|проектирован|изыскан|содержан|обустройств|разработк|капитальн").matcher(s).find();
    }
    static boolean candidateKazan(String project,String workAddress) {
        String place=project+" "+workAddress;
        if(!CITY.matcher(place).find())return false;
        // Do not infer the location of works from the contracting authority's legal address.
        if(!CITY.matcher(project).find() && OTHER_CITY.matcher(project).find())return false;
        if(!CITY.matcher(project).find() && workAddress.toLowerCase(Locale.ROOT).contains("заказчик"))return false;
        // An explicitly different municipality may appear alongside a buyer located in Kazan.
        if(OTHER_CITY.matcher(workAddress).find()&&!CITY.matcher(workAddress).find())return false;
        return true;
    }
    static String noticeNumber(String s) {Matcher m=NOTICE.matcher(s);return m.find()?m.group():"";}
    static String program(String text) {
        String s=text.toLowerCase(Locale.ROOT);
        if(s.contains("инфраструктур")&&s.contains("для жизни"))return "Инфраструктура для жизни";
        if(s.contains("безопасн")&&s.contains("качественн")&&s.contains("дорог"))return "Безопасные качественные дороги";
        if(s.contains("жилье и городск")||s.contains("жильё и городск"))return "Жильё и городская среда";
        if(s.contains("комплексн")&&s.contains("развит")&&s.contains("территор"))return "Комплексное развитие территорий";
        if(s.contains("республиканск")&&s.contains("программ"))return "Республиканская программа";
        return ""; // unknown != not funded
    }
    static Map<String,Object> procurement(String project,String url,String externalId,String place,String buyer,String program,String origin,String date) {
        if(!roadWorks(project)||!candidateKazan(project,place))throw new IllegalArgumentException("Not a Kazan road-work candidate");
        String scheme=program.isBlank()?program(project+" "+place):program;
        String desc="Предмет: "+project+(place.isBlank()?"":". Место по объявлению: "+place)
            +(buyer.isBlank()?"":". Заказчик: "+buyer)
            +(scheme.isBlank()?". Программа финансирования не указана/не распознана.":". Заявленная программа: "+scheme+".")
            +" Это ТЕКСТОВАЯ привязка к Казани, границы работ и стадия закупки требуют проверки в ЕИС.";
        return event("EIS_ROADS",project,url,externalId,desc,date,"KAZAN_MENTION",scheme,buyer,"PROCUREMENT");
    }
}
