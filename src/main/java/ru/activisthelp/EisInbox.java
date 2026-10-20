package ru.activisthelp;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/**
 * Fallback for EIS when its website is inaccessible: read independently obtained
 * public XML exports and normalized CSV files from a local directory, without login.
 * No attempt is made to bypass CAPTCHA or invent a public EIS API.
 */
public final class EisInbox {
    private EisInbox() {}
    private static final int MAX_FILE=6_000_000;
    record Result(List<Map<String,Object>> events,String note) {}

    static Result scan(String path) throws IOException {
        if(path==null||path.isBlank())return new Result(List.of(),
            "Локальная загрузка отключена. Задайте ACTIVIST_EIS_INBOX с каталогом выгрузок ЕИС XML/CSV/ZIP. Доступ к ЕИС через HTML может ограничиваться.");
        Path dir=Path.of(path);
        if(!Files.isDirectory(dir)||Files.isSymbolicLink(dir))return new Result(List.of(),"Каталог не найден или является символической ссылкой: "+dir);
        List<Path> files;
        try(var items=Files.list(dir)) {
            files=items.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                .filter(p->p.getFileName().toString().matches("(?i).+\\.(csv|xml|zip)"))
                .sorted(Comparator.comparing(p->p.getFileName().toString())).limit(30).toList();
        }
        List<Map<String,Object>> events=new ArrayList<>();int read=0;List<String> errors=new ArrayList<>();
        for(Path file:files) {
            try {
                if(Files.size(file)>MAX_FILE)throw new IOException("size limit 6 MB");
                String ext=file.getFileName().toString().replaceAll("^.*\\.","").toLowerCase(Locale.ROOT);
                if(ext.equals("zip")){
                    try(ZipInputStream zin=new ZipInputStream(Files.newInputStream(file))) {
                        ZipEntry entry;int entries=0;
                        while((entry=zin.getNextEntry())!=null && ++entries<=35){
                            String name=entry.getName();
                            if(entry.isDirectory()||(!name.toLowerCase(Locale.ROOT).endsWith(".xml")&&!name.toLowerCase(Locale.ROOT).endsWith(".csv")))continue;
                            byte[] b=zin.readNBytes(MAX_FILE+1);
                            if(b.length>MAX_FILE)throw new IOException("oversized ZIP entry");
                            events.addAll(name.toLowerCase(Locale.ROOT).endsWith(".csv")?csv(new String(b,StandardCharsets.UTF_8)):xml(b));
                        }
                    }
                } else {
                    byte[] bytes=Files.readAllBytes(file);
                    events.addAll(ext.equals("csv")?csv(new String(bytes,StandardCharsets.UTF_8)):xml(bytes));
                }
                read++;
            }catch(Exception e){errors.add(file.getFileName()+": "+e.getClass().getSimpleName()+" — "+String.valueOf(e.getMessage()));}
        }
        String msg="Прочитано локальных архивов/файлов: "+read+", найдено дорожных закупок с упоминанием Казани: "+events.size()+". ";
        msg+="Повторные номера извещений игнорируются при сохранении.";
        if(!errors.isEmpty())msg+=" Ошибки: "+String.join("; ",errors).substring(0,Math.min(800,String.join("; ",errors).length()));
        return new Result(events,msg);
    }
    /** Normalized CSV export, NOT an assertion about EIS' evolving CSV column names. */
    static List<Map<String,Object>> csv(String content) {
        if(content.startsWith("\uFEFF"))content=content.substring(1);
        List<List<String>> rows=csvRows(content);
        if(rows.size()<2)return List.of();
        Map<String,Integer> columns=new HashMap<>();
        for(int i=0;i<rows.get(0).size();i++)columns.put(key(rows.get(0).get(i)),i);
        List<Map<String,Object>> out=new ArrayList<>();
        for(int i=1;i<rows.size()&&i<=2000;i++) {
            List<String> row=rows.get(i);
            String title=col(row,columns,"title","subject","object","purchaseobjectinfo","объектзакупки","наименованиеобъектазакупки");
            String address=col(row,columns,"workaddress","location","worklocation","адресработ","местовыполненияработ");
            String number=col(row,columns,"noticenumber","purchasenumber","номеризвещения","номерзакупки");
            String customer=col(row,columns,"customer","buyer","заказчик");
            String prog=col(row,columns,"program","программа");
            String date=date(col(row,columns,"publishedat","publicationdate","датапубликации"));
            String url=col(row,columns,"url","link","ссылканаизвещение");
            if(!OfficialSources.roadWorks(title)||!OfficialSources.candidateKazan(title,address))continue;
            if(number.isBlank())number="csv-"+Integer.toHexString(Objects.hash(title,address,date));
            out.add(OfficialSources.procurement(shorter(title,690),safeUrl(url,number),number,address,customer,prog,"ЕИС (локальная выгрузка)",date));
        }
        return out;
    }
    static List<Map<String,Object>> xml(byte[] bytes)throws Exception {
        if(bytes.length>MAX_FILE)throw new IOException("XML size limit");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        Document doc=f.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(bytes)));
        String title=first(doc,"purchaseObjectInfo","purchaseObjectName","objectName","nameOfObject","nameOfPurchase");
        String number=first(doc,"purchaseNumber","notificationNumber","registrationNumber","regNumber","number");
        String place=first(doc,"placeOfDelivery","deliveryPlace","workAddress","placeOfWork","location","deliveryPlaceText");
        String buyer=first(doc,"fullName","customerName","organizationName");
        String program=first(doc,"programName","federalProject","nationalProject");
        String when=date(first(doc,"publishDate","createDate","publicationDate"));
        String url=first(doc,"href","url","noticeUrl");
        if(!OfficialSources.roadWorks(title)||!OfficialSources.candidateKazan(title,place))return List.of();
        if(number.isBlank())number="xml-"+Integer.toHexString(Objects.hash(title,place,when));
        return List.of(OfficialSources.procurement(shorter(title,690),safeUrl(url,number),number,shorter(place,500),
            shorter(buyer,200),shorter(program,180),"ЕИС (локальный XML)",when));
    }
    private static String first(Document d,String...tags) {
        for(String tag:tags) {
            NodeList nodes=d.getElementsByTagNameNS("*",tag);
            if(nodes.getLength()==0)nodes=d.getElementsByTagName(tag);
            for(int i=0;i<Math.min(nodes.getLength(),40);i++) {
                String text=nodes.item(i).getTextContent();
                if(text!=null&&!text.isBlank()&&text.length()<3500)return text.trim().replaceAll("\\s+"," ");
            }
        }
        return "";
    }
    private static String safeUrl(String val,String number) {
        try {
            var uri=java.net.URI.create(val);
            if("https".equals(uri.getScheme())&&"zakupki.gov.ru".equalsIgnoreCase(uri.getHost()))return uri.toString();
        }catch(Exception ignored){}
        return OfficialSources.EIS_URL+"?searchString="+URLEncoder.encode(number,StandardCharsets.UTF_8);
    }
    private static String key(String s){return s.toLowerCase(Locale.ROOT).replace("ё","е").replaceAll("[^a-zа-я0-9]","");}
    private static String col(List<String> row,Map<String,Integer> cols,String...aliases) {
        for(String alias:aliases){Integer i=cols.get(key(alias));if(i!=null&&i<row.size())return row.get(i).trim();}
        return "";
    }
    private static String shorter(String s,int len){return s.length()>len?s.substring(0,len):s;}
    private static String date(String s){
        if(s.matches("\\d{4}-\\d{2}-\\d{2}.*"))return s.substring(0,10);
        if(s.matches("\\d{2}\\.\\d{2}\\.\\d{4}.*")){
            try{return LocalDate.parse(s.substring(0,10),DateTimeFormatter.ofPattern("dd.MM.uuuu")).toString();}
            catch(Exception ignored){}
        }
        return "";
    }
    private static List<List<String>> csvRows(String content) {
        // Accept semicolon (usual Russian export) and comma, including quoted delimiters and newlines.
        int n1=0,n2=0;boolean quoted=false;
        for(char c:content.substring(0,Math.min(content.length(),1500)).toCharArray()){
            if(c=='"')quoted=!quoted;
            if(!quoted){if(c==';')n1++;if(c==',')n2++;if(c=='\n')break;}
        }
        char sep=n1>=n2?';':',';
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();quoted=false;
        for(int i=0;i<content.length();i++){
            char c=content.charAt(i);
            if(c=='"'){
                if(quoted&&i+1<content.length()&&content.charAt(i+1)=='"'){field.append('"');i++;}
                else quoted=!quoted;
            }else if(!quoted&&(c==sep||c=='\n'||c=='\r')) {
                if(c==sep){row.add(field.toString());field.setLength(0);}
                else {
                    if(c=='\r'&&i+1<content.length()&&content.charAt(i+1)=='\n')i++;
                    row.add(field.toString());rows.add(row);row=new ArrayList<>();field.setLength(0);
                }
            } else field.append(c);
            if(rows.size()>2001)break;
        }
        if(field.length()>0||!row.isEmpty()){row.add(field.toString());rows.add(row);}
        return rows;
    }
}
