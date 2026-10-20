package ru.activisthelp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Deterministic end-to-end tests with mocked EIS transport and synthetic annex text. */
public final class EisAndAnnexTest {
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        String token="example-NOT-A-REAL-TOKEN";
        String request=EisSoapClient.request(token,LocalDate.of(2026,10,19),"epNotificationEF2020",
            LocalDateTime.of(2026,10,20,8,35),UUID.fromString("96da8e91-0000-4000-9000-12aacbeaf000"));
        assert request.contains("<orgRegion>16</orgRegion><subsystemType>PRIZ</subsystemType>");
        assert request.contains("<documentType44>epNotificationEF2020</documentType44>");
        assert request.contains("<exactDate>2026-10-19</exactDate>");
        assert request.contains("<individualPerson_token>"+token+"</individualPerson_token>");
        assert !EisSoapClient.safeArchiveUrl("https://int44.zakupki.gov.ru.evil.example/file.zip");
        assert !EisSoapClient.safeArchiveUrl("http://zakupki.gov.ru/file.zip");
        assert !EisSoapClient.safeArchiveUrl("https://user@zakupki.gov.ru/file.zip");
        assert EisSoapClient.safeArchiveUrl("https://int44.zakupki.gov.ru/warehouse/file.zip");
        String example="""
            <env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/" xmlns:d="urn:test">
               <env:Body><d:getDocsByOrgRegionResponse><dataInfo>
                 <archiveUrl>https://int44.zakupki.gov.ru/export/2026.zip</archiveUrl>
               </dataInfo></d:getDocsByOrgRegionResponse></env:Body>
            </env:Envelope>""";
        assert EisSoapClient.archiveUrl(utf(example)).endsWith("2026.zip");
        boolean bad=false;
        try{EisSoapClient.archiveUrl(utf(example.replace("int44.zakupki.gov.ru","evil.invalid")));}
        catch(IOException expected){bad=true;}assert bad;
        String xml="""
            <epNotificationEF2020 xmlns="urn:test">
             <purchaseNumber>0123456789012345678</purchaseNumber>
             <purchaseObjectInfo>Ремонт автомобильной дороги на ул. Несмелова</purchaseObjectInfo>
             <placeOfDelivery>г. Казань, улица Несмелова</placeOfDelivery>
             <fullName>ГКУ Главтатдортранс</fullName>
             <publishDate>2026-10-19</publishDate>
            </epNotificationEF2020>""";
        byte[] zipped;
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("one.xml"));zip.write(utf(xml));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("two.xml"));zip.write(utf(xml));zip.closeEntry();
            zip.finish();zipped=out.toByteArray();
        }
        var result=EisSoapClient.scan(token,LocalDate.of(2026,10,19),List.of("epNotificationEF2020"),
            req->{assert req.contains(token);return utf(example);},
            url->{assert url.contains("int44.zakupki.gov.ru");return zipped;});
        assert result.events().size()==1:result;
        assert "EIS_SOAP".equals(result.events().get(0).get("source"));
        assert result.note().contains("1 городских кандидатов");
        String malicious="<!DOCTYPE z [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><foo>&x;</foo>";
        boolean block=false;
        try {EisSoapClient.archiveUrl(utf(malicious));}catch(Exception ex){block=true;}
        assert block;
        String pdfIndex="""
            <div>Постановление о дорожных работах в РТ 2026 году.
               <a href="/file/npa/2026-05/road.pdf">Скачать PDF</a></div>
            <div>Постановление об утверждении порядка школ
               <a href="/file/npa/2026-05/schools.pdf">Скачать PDF</a></div>""";
        var links=RoadAnnex.candidatePdfs(RoadAnnex.RT,pdfIndex,"pravo.tatarstan.ru");
        assert links.size()==1:links;
        assert links.get(0).endsWith("road.pdf");
        String page="""
            Объекты республиканской программы дорожного строительства на 2026 год
            г. Казань
            1. Ремонт ул. Несмелова от улицы Серова до Кировской дамбы
            2. Реконструкция дороги по Горьковскому шоссе
            г. Набережные Челны
            3. Ремонт улицы Центральной
            """;
        var extracted=RoadAnnex.extractPage(page,"https://pravo.tatarstan.ru/file/npa/road.pdf","RT_PDF_ANNEX",21);
        assert extracted.size()==2:extracted;
        assert "21".equals(String.valueOf(extracted.get(0).get("annexPage")));
        assert "KAZAN_MENTION".equals(extracted.get(0).get("locationScope"));
        assert Boolean.TRUE.equals(extracted.get(0).get("requiresReview"));
        System.out.println("EisAndAnnexTest: SOAP contract, hostile URLs, ZIP/dedup, XML and annex extraction passed.");
    }
}
