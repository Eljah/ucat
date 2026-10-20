package ru.activisthelp;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/**
 * Opt-in SOAP client for the EIS machine-readable document delivery service for individuals.
 * Nothing is requested until ACTIVIST_EIS_TOKEN is supplied by the operator.
 * The integration requires current EIS XSD validation and a live integration test.
 */
public final class EisSoapClient {
    public static final String ENDPOINT="https://int44.zakupki.gov.ru/eis-integration/services/getDocsIP";
    private static final int MAX_REPLY=1_000_000;
    private static final int MAX_ARCHIVE=30_000_000;
    private static final DateTimeFormatter LOCAL=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    private static final String DEFAULT_TYPE="epNotificationEF2020";
    private EisSoapClient() {}

    record Result(List<Map<String,Object>> events,String note) {}
    @FunctionalInterface interface SoapTransport { byte[] send(String xml) throws Exception; }
    @FunctionalInterface interface ArchiveTransport { byte[] get(String url) throws Exception; }

    static String request(String token,LocalDate date,String type,LocalDateTime created,UUID id) {
        if(token==null||token.isBlank())throw new IllegalArgumentException("Missing EIS token");
        if(!type.matches("[A-Za-z][A-Za-z0-9]{4,100}"))throw new IllegalArgumentException("Unsupported document type syntax");
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ws="http://zakupki.gov.ru/fz44/get-docs-ip/ws">
              <soapenv:Header><individualPerson_token>%s</individualPerson_token></soapenv:Header>
              <soapenv:Body><ws:getDocsByOrgRegionRequest>
                <index><id>%s</id><createDateTime>%s</createDateTime><mode>PROD</mode></index>
                <selectionParams><orgRegion>16</orgRegion><subsystemType>PRIZ</subsystemType>
                  <documentType44>%s</documentType44><periodInfo><exactDate>%s</exactDate></periodInfo>
                </selectionParams>
              </ws:getDocsByOrgRegionRequest></soapenv:Body>
            </soapenv:Envelope>
            """.formatted(escape(token),id,created.format(LOCAL),type,date);
    }
    private static String escape(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("\"","&quot;").replace("'","&apos;");
    }
    static Document secureXml(byte[] bytes) throws Exception {
        if(bytes.length>MAX_REPLY)throw new IOException("EIS response exceeded size limit");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return f.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(bytes)));
    }
    static String archiveUrl(byte[] reply) throws Exception {
        Document doc=secureXml(reply);
        NodeList faults=doc.getElementsByTagNameNS("*","Fault");
        if(faults.getLength()>0)throw new IOException("EIS returned a SOAP Fault (details withheld to protect credentials)");
        NodeList urls=doc.getElementsByTagNameNS("*","archiveUrl");
        if(urls.getLength()==0)urls=doc.getElementsByTagName("archiveUrl");
        if(urls.getLength()==0)return ""; // noData can be valid for a date/type
        String url=urls.item(0).getTextContent().trim();
        if(!url.isEmpty()&&!safeArchiveUrl(url))throw new IOException("EIS supplied an unsafe archive URL");
        return url;
    }
    static boolean safeArchiveUrl(String raw) {
        try {
            URI uri=URI.create(raw);
            String h=uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && h!=null &&
                (h.equalsIgnoreCase("zakupki.gov.ru")||h.toLowerCase(Locale.ROOT).endsWith(".zakupki.gov.ru")) &&
                uri.getUserInfo()==null && uri.getPort()==-1 && uri.getFragment()==null;
        }catch(Exception e){return false;}
    }
    private static byte[] limited(InputStream is,int limit)throws IOException {
        try(is){byte[] bytes=is.readNBytes(limit+1);
            if(bytes.length>limit)throw new IOException("EIS response exceeded size limit");
            return bytes;}
    }
    static Result scan(String token,LocalDate date,List<String> types,SoapTransport soap,ArchiveTransport archive)throws Exception {
        if(token==null||token.isBlank())return new Result(List.of(),"Не настроено: нужен ACTIVIST_EIS_TOKEN из кабинета получателя открытых данных ЕИС.");
        if(date.isAfter(LocalDate.now(ZoneId.of("Europe/Moscow")))||date.isBefore(LocalDate.of(2025,1,1)))
            throw new IllegalArgumentException("Invalid archive date");
        List<Map<String,Object>> all=new ArrayList<>();List<String> notes=new ArrayList<>();
        Set<String> unique=new HashSet<>();
        for(String type:types.stream().distinct().limit(5).toList()) {
            try {
                byte[] response=soap.send(request(token,date,type,LocalDateTime.now(ZoneId.of("Europe/Moscow")),UUID.randomUUID()));
                String url=archiveUrl(response);
                if(url.isBlank()){notes.add(type+": noData/нет archiveUrl");continue;}
                byte[] bytes=archive.get(url);
                if(bytes.length>MAX_ARCHIVE)throw new IOException("Archive size exceeds 30 MB");
                List<Map<String,Object>> found=EisInbox.zip(bytes);
                int kept=0;
                for(var event:found) {
                    String id=Json.string(event.get("externalId"));
                    if(unique.add(id)) {event.put("source","EIS_SOAP");event.put("origin","ЕИС getDocsIP; тип "+type);all.add(event);kept++;}
                }
                notes.add(type+": "+kept+" городских кандидатов");
            }catch(Exception e){ // A failing type must not suppress other kinds of notices.
                notes.add(type+": ошибка "+e.getClass().getSimpleName());
            }
        }
        return new Result(all,"Дата ЕИС "+date+" (регион заказчика 16, не границы работ): "+String.join("; ",notes));
    }
    static List<String> configuredTypes(String config) {
        if(config==null||config.isBlank())return List.of(DEFAULT_TYPE);
        List<String> list=new ArrayList<>();
        for(String part:config.split(",")) {String t=part.strip();if(t.matches("[A-Za-z][A-Za-z0-9]{4,100}"))list.add(t);}
        return list.isEmpty()?List.of(DEFAULT_TYPE):List.copyOf(list);
    }
    public static Result scanFromEnvironment() throws Exception {
        String token=System.getenv("ACTIVIST_EIS_TOKEN");
        if(token==null||token.isBlank())return new Result(List.of(),"Ожидает регистрации в ЕИС: переменная ACTIVIST_EIS_TOKEN не задана. Без токена сеть не используется.");
        LocalDate today=LocalDate.now(ZoneId.of("Europe/Moscow"));
        String manual=System.getenv("ACTIVIST_EIS_DATE");
        LocalDate from;
        try {from=manual==null||manual.isBlank()?today:LocalDate.parse(manual);}
        catch(Exception ex){throw new IllegalArgumentException("ACTIVIST_EIS_DATE must be YYYY-MM-DD");}
        HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(12)).build();
        SoapTransport soap=xml->{
            HttpRequest r=HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(Duration.ofSeconds(45))
                .header("Content-Type","text/xml;charset=utf-8").header("Accept","application/xml,text/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xml,StandardCharsets.UTF_8)).build();
            HttpResponse<InputStream> response=client.send(r,HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()!=200){response.body().close();throw new IOException("SOAP HTTP "+response.statusCode());}
            return limited(response.body(),MAX_REPLY);
        };
        ArchiveTransport archive=url->{
            if(!safeArchiveUrl(url))throw new IOException("Unsafe archive host");
            HttpRequest r=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(110))
                .header("individualPerson_token",token).header("Accept","application/zip,application/octet-stream")
                .GET().build();
            HttpResponse<InputStream> resp=client.send(r,HttpResponse.BodyHandlers.ofInputStream());
            if(resp.statusCode()!=200){resp.body().close();throw new IOException("Archive HTTP "+resp.statusCode());}
            return limited(resp.body(),MAX_ARCHIVE);
        };
        List<String> types=configuredTypes(System.getenv("ACTIVIST_EIS_TYPES"));
        Result current=scan(token,from,types,soap,archive);
        if(manual!=null&&!manual.isBlank())return current;
        // Today's publications are time-critical; yesterday is rechecked for delivery lag.
        Result previous=scan(token,today.minusDays(1),types,soap,archive);
        List<Map<String,Object>> events=new ArrayList<>(current.events());Set<String> seen=new HashSet<>();
        for(var e:events)seen.add(Json.string(e.get("externalId")));
        for(var e:previous.events())if(seen.add(Json.string(e.get("externalId"))))events.add(e);
        return new Result(events,current.note()+" | "+previous.note());
    }
}
