package ru.activisthelp;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * A cautious best-effort PDF text-layer reader. No automatic geocoding, no OCR and no
 * claim of complete annex processing: scans, drawings and split table columns need review.
 */
public final class RoadAnnex {
    private RoadAnnex() {}
    static final String KZN="https://pravo.tatarstan.ru/kazan/ispolkom?limit=50";
    static final String RT="https://pravo.tatarstan.ru/npa_kabmin/post?limit=50";
    static final String PLAN="https://mindortrans.tatarstan.ru/programma-dorozhnih-rabot-na-2026-god.htm";
    private static final int MAX_PDF=32_000_000;
    private static final int MAX_PAGES=180;
    private static final Pattern CITY=Pattern.compile("(?Uiu)(?:г\\.?\\s*Казан[ьи]|город\\s+Казан[ьи]|городск\\w*\\s+округ\\s+Казан[ьи]|\\bКазан[ьи]\\b)");
    private static final Pattern DISTRICT=Pattern.compile("(?Uiu)(?:Альметьевск|Набережн\\w*\\s+Челн|Нижнекамск|Зеленодольск|Елабуг[аи]|Арск|Буинск|Чистопол|Лаишевск)");
    private static final Pattern ROAD=Pattern.compile("(?Uiu)(?:ул\\.|улиц[аые]|проспект|пр\\.|шоссе|путепровод|дорог[аиуы]|проезд|пер\\.|переулок|мост|развязк|тротуар|ремонт|строительств|реконструкц|планировк|межеван|сервитут)");
    private static final Pattern DOCUMENT=Pattern.compile("(?Uiu)\\b(?:дорог|дорожн|планиров|межеван|путепровод|реконструкц|строительств|транспорт|улиц|ремонт|сервитут)\\w*");
    @FunctionalInterface interface PageFetcher {byte[] fetch(String url)throws Exception;}
    record Result(List<Map<String,Object>> events,String note) {}

    /** Select PDF links only when the neighbouring text is about roads/territory.
     *  This does not assert that the index contains ALL the documents. */
    private static final Pattern ANCHOR=Pattern.compile("<a\\b[^>]*?href\\s*=\\s*(['\"])(.*?)\\1[^>]*>.*?</a\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    static List<String> candidatePdfs(String base,String html,String host) {
        List<String> pdfs=new ArrayList<>();Matcher m=ANCHOR.matcher(html);
        while(m.find()) {
            String url=OfficialSources.trusted(base,m.group(2),host);
            if(url==null||!url.matches("(?i).*\\.pdf(?:\\?.*)?$"))continue;
            String preceding=html.substring(Math.max(0,m.start()-1050),m.start());
            // Do not use the previous decree's heading for this PDF.
            int boundary=-1;
            for(String marker:List.of("<div", "</div>","<li", "</li>","<tr", "</tr>","<article", "</article>"))
                boundary=Math.max(boundary,preceding.lastIndexOf(marker));
            if(boundary>=0)preceding=preceding.substring(boundary);
            String context=OfficialSources.plain(preceding+" "+m.group());
            if(!DOCUMENT.matcher(context).find())continue;
            if(!pdfs.contains(url))pdfs.add(url);
            if(pdfs.size()>=3)break;
        }
        return pdfs;
    }
    static Result scanUrl(String url,OfficialSources.Downloader page,PageFetcher file) throws Exception {
        URI u=URI.create(url);
        String host=u.getHost();
        if(!List.of("pravo.tatarstan.ru","mindortrans.tatarstan.ru").contains(host))
            throw new IllegalArgumentException("Unapproved website");
        String html=page.get(url);
        List<String> pdfs=candidatePdfs(url,html,host);
        List<Map<String,Object>> events=new ArrayList<>();int read=0,needsOcr=0;List<String> failures=new ArrayList<>();
        for(String link:pdfs){
            try {
                byte[] bytes=file.fetch(link);
                var extracted=readPdf(bytes,link,host.equals("pravo.tatarstan.ru")&&url.contains("kazan")?"KZN_PDF_ANNEX":"RT_PDF_ANNEX");
                events.addAll(extracted.events());read++;
                if(extracted.note().contains("не содержит текстового слоя"))needsOcr++;
            }catch(Exception e){failures.add(e.getClass().getSimpleName());}
        }
        String note="В индексе обнаружено кандидатов PDF: "+pdfs.size()+", прочитано: "+read+
            ", найдено кандидатов на дорожные объекты: "+events.size()+"; PDF без текста: "+needsOcr+
            ". Извлечение таблиц и геометрии требует проверки.";
        if(!failures.isEmpty())note+=" Ошибки файлов: "+String.join(",",failures);
        return new Result(events,note);
    }
    static Result readPdf(byte[] bytes,String pdfUrl,String source)throws Exception {
        if(bytes.length>MAX_PDF)throw new IOException("PDF exceeds 32MB limit");
        if(bytes.length<5||bytes[0]!='%'||bytes[1]!='P'||bytes[2]!='D'||bytes[3]!='F')
            throw new IOException("Not a PDF");
        List<Map<String,Object>> out=new ArrayList<>();int textPages=0;
        try(PDDocument pdf=Loader.loadPDF(bytes)) {
            int pages=Math.min(MAX_PAGES,pdf.getNumberOfPages());
            PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            for(int p=1;p<=pages;p++) {
                stripper.setStartPage(p);stripper.setEndPage(p);
                String text=stripper.getText(pdf);
                if(!text.isBlank()){textPages++;out.addAll(extractPage(text,pdfUrl,source,p));}
                if(out.size()>=120)break;
            }
            String note="Просмотрено страниц: "+pages+"/"+pdf.getNumberOfPages()+
                ", текстовых страниц: "+textPages+", кандидатов: "+out.size()+".";
            if(textPages==0)note+=" PDF не содержит текстового слоя: требуется OCR.";
            if(pdf.getNumberOfPages()>pages)note+=" Остальные страницы пока не проверены.";
            return new Result(out,note);
        }
    }
    static List<Map<String,Object>> extractPage(String page,String url,String source,int pageNumber) {
        String[] lines=page.split("\\R");List<Map<String,Object>> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        boolean kazanSection=false;int sectionAge=0;
        for(int i=0;i<lines.length;i++) {
            String line=lines[i].strip().replaceAll("\\s+"," ");
            if(line.isBlank())continue;
            boolean city=CITY.matcher(line).find();boolean other=DISTRICT.matcher(line).find();
            boolean section=city && (line.length()<75||line.matches("(?Uiu).*?(?:городск\\w*\\s+округ|муниципальн\\w*\\s+образован).*"));
            if(section){kazanSection=true;sectionAge=0;}
            else if(other&&!city){kazanSection=false;}
            else if(++sectionAge>48)kazanSection=false;
            boolean aRoad=ROAD.matcher(line).find();
            if(!aRoad||(!city&&!kazanSection)||line.length()<15||line.length()>420)continue;
            // Avoid false positives from the standard legal preamble and table headers.
            if(line.matches("(?Uiu).*(?:в соответствии с федеральным|контроль за исполнением|настоящим постановлением|премьер-министр|прилагаемый перечень|наименование муниципального).*"))continue;
            if(other&&!city)continue;
            String normalized=line.replaceAll("\\s+"," ").strip();
            if(!seen.add(normalized.toLowerCase(Locale.ROOT)))continue;
            String fingerprint=hex(url+"#"+pageNumber+"#"+normalized);
            String title="Из приложения, стр. "+pageNumber+": "+normalized;
            if(title.length()>290)title=title.substring(0,290);
            Map<String,Object> event=Json.obj("source",source,"title",title,"url",url,
                "externalId",fingerprint,"description","Фрагмент PDF-приложения: "+normalized+
                    ". Город указан в документе, но границы и реальный участок работ НЕ установлены; требуется ручная сверка таблицы и чертежа.",
                "documentKind","ANNEX_ROAD_CANDIDATE","locationScope","KAZAN_MENTION",
                "annexPage",pageNumber,"evidenceText",normalized,"requiresReview",true,"publishedAt","");
            result.add(event);
            if(result.size()>=35)break;
        }
        return result;
    }
    private static String hex(String s) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)),0,16);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    /** Local fallback for archives/documents downloaded manually from official portals. */
    static Result scanLocal(String path)throws Exception {
        if(path==null||path.isBlank())return new Result(List.of(),"Локальный импорт отключен: установите ACTIVIST_ANNEX_INBOX.");
        Path dir=Path.of(path);
        if(!Files.isDirectory(dir)||Files.isSymbolicLink(dir))return new Result(List.of(),"Каталог PDF не найден.");
        List<Map<String,Object>> all=new ArrayList<>();int files=0,ocr=0;List<String> errors=new ArrayList<>();
        try(var list=Files.list(dir)) {
            for(Path pdf:list.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).limit(20).toList()) {
                try {
                    if(Files.size(pdf)>MAX_PDF)throw new IOException("Too large");
                    Result r=readPdf(Files.readAllBytes(pdf),pdf.toUri().toString(),"LOCAL_PDF_ANNEX");
                    for(Map<String,Object> item:r.events()) {
                        item.put("url",""); // Browser must never be offered a file:// link.
                        item.put("description","Локальный PDF «"+pdf.getFileName()+"». "+item.get("description"));
                        all.add(item);
                    }
                    files++;if(r.note().contains("не содержит текстового слоя"))ocr++;
                }catch(Exception e){errors.add(pdf.getFileName()+": "+e.getClass().getSimpleName());}
            }
        }
        String note="Прочитано локальных PDF: "+files+", извлечено кандидатов: "+all.size()+", без текста: "+ocr;
        if(!errors.isEmpty())note+="; ошибки: "+String.join(", ",errors);
        return new Result(all,note);
    }
    static byte[] fetchPdf(String url)throws Exception {
        URI uri=URI.create(url);String host=uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getPort()!=-1||uri.getUserInfo()!=null||
           !List.of("pravo.tatarstan.ru","mindortrans.tatarstan.ru").contains(host))throw new IOException("Unapproved PDF host");
        HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(12)).build();
        HttpRequest req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120))
            .header("User-Agent","Ucat/0.2 (public read-only research)").header("Accept","application/pdf")
            .GET().build();
        HttpResponse<InputStream> reply=client.send(req,HttpResponse.BodyHandlers.ofInputStream());
        if(reply.statusCode()!=200){reply.body().close();throw new IOException("PDF HTTP "+reply.statusCode());}
        try(InputStream in=reply.body()) {
            byte[] bytes=in.readNBytes(MAX_PDF+1);
            if(bytes.length>MAX_PDF)throw new IOException("PDF exceeds size limit");
            return bytes;
        }
    }
}
