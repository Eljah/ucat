package ru.activisthelp;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic parser tests. These fixtures do not assert that the official sites are reachable. */
public final class RoadSourcesTest {
    public static void main(String[] args) throws Exception {
        String bulletin="""
            <html><body>
            <p><b>Постановление Исполнительного комитета г.Казани от 23.04.2026 №1349</b></p>
            <p>Об изъятии земельных участков в связи со строительством автомобильной дороги от дублера Горьковского шоссе до ул.Приволжская</p>
            <p><b>Постановление Исполнительного комитета г.Казани от 27.04.2026 №1435</b></p>
            <p>О внесении изменений в проект планировки и межевания территории автомобильной дороги</p>
            <p><b>Постановление Исполнительного комитета г.Казани от 24.04.2026 №1393</b></p>
            <p>О награждении работников Почетной грамотой</p>
            </body></html>
            """;
        String oneIssue="https://kzn.ru/meriya/press-tsentr/novosti/budet-opublikovano-setevoe-izdanie-2026/";
        var acts=OfficialSources.municipalBulletin(oneIssue,bulletin);
        assert acts.size()==2 : acts;
        assert !acts.get(0).get("externalId").equals(acts.get(1).get("externalId"));
        assert "KZN_ACT".equals(acts.get(0).get("source"));
        assert "2026-04-23".equals(acts.get(0).get("issuedAt"));
        assert "".equals(acts.get(0).get("publishedAt")):"Act signing date is not its publication date";
        String index="<a href='/meriya/press-tsentr/novosti/budet-opublikovano-setevoe-izdanie-2026/'>Будет опубликовано сетевое издание</a>";
        var results=OfficialSources.collect("KZN_BULLETINS",url->{
            if(url.equals(OfficialSources.KZN_NEWS_URL))return index;
            if(url.equals(oneIssue))return bulletin;
            throw new IllegalArgumentException("unmocked url "+url);
        });
        assert results.events().size()==2 : results;
        String rtHtml="""
            <div>Постановление Кабинета Министров Республики Татарстан от 22.01.2026 №30
            «О дорожных работах на дорогах общего пользования в Республике Татарстан на 2026-2028 годы»
            <a href='/file/pub/pub_3000.pdf'>PDF</a></div>
            <div><a href='/documents/normativ_mintrans_rt.htm?pub_id=123'>Приказ об организации такси</a></div>
            """;
        var rt=OfficialSources.ministryIndex(OfficialSources.RT_ROADPLAN_URL,rtHtml,true);
        assert rt.size()==1:rt;
        assert "REGIONAL_REVIEW".equals(rt.get(0).get("locationScope"));
        String eis="""
            <div class="search-registry-entry-block"><a href="/epz/order/notice/ea20/view/common-info.html?regNumber=0123456789012345678">№ 0123456789012345678</a>
              Объект закупки: Реконструкция автомобильной дороги по улице Несмелова г. Казани
              Заказчик: ГКУ дорожное хозяйство. Начальная цена: 99 рублей.
            </div>
            <div class="search-registry-entry-block"><a href="/epz/order/notice/ea20/view/common-info.html?regNumber=0123456789012345679">№ 0123456789012345679</a>
              Объект закупки: Капитальный ремонт автодороги в г. Нижнекамске
              Заказчик: Казанская администрация. Начальная цена: 999 рублей.
            </div>
            """;
        var tenders=OfficialSources.eisHtml(OfficialSources.EIS_URL,eis);
        assert tenders.size()==1 : tenders;
        assert "PROCUREMENT".equals(tenders.get(0).get("documentKind"));
        assert !OfficialSources.candidateKazan("Ремонт автомобильной дороги в Нижнекамске","Заказчик расположен в Казани"):
            "Buyer city is not an object location";
        assert !OfficialSources.roadWorks("поставка дорожной соли для школы Казани");
        assert OfficialSources.roadWorks("Проектирование путепровода в Казани");
        assert "Инфраструктура для жизни".equals(OfficialSources.program("национальный проект Инфраструктура для жизни"));
        String csv="""
            notice_number;title;work_address;customer;published_at;program;url
            0123456789012345678;"Реконструкция автомобильной дороги";г. Казань, ул. Несмелова;Главтатдортранс;23.04.2026;Инфраструктура для жизни;https://zakupki.gov.ru/epz/order/notice/ea20/view/common-info.html?regNumber=0123456789012345678
            0123456789012345679;"Строительство автомобильной дороги";г. Альметьевск;Заказчик расположен в Казани;24.04.2026;;
            """;
        var imported=EisInbox.csv(csv);
        assert imported.size()==1:imported;
        assert "2026-04-23".equals(imported.get(0).get("publishedAt"));
        String xml="""
            <?xml version="1.0" encoding="utf-8"?>
            <purchaseNotice xmlns="urn:example:eis"><notificationNumber>0123456789012345680</notificationNumber>
            <purchaseObjectInfo>Проектирование автомобильной дороги</purchaseObjectInfo>
            <placeOfDelivery>город Казань, ул. Тверская</placeOfDelivery><customerName>ГКУ РТ</customerName>
            <publishDate>2026-04-29</publishDate></purchaseNotice>
            """;
        var fromXml=EisInbox.xml(xml.strip().getBytes(StandardCharsets.UTF_8));
        assert fromXml.size()==1 : fromXml;
        String unsafe="<!DOCTYPE foo [ <!ENTITY xxe SYSTEM 'file:///etc/passwd'> ]><foo>&xxe;</foo>";
        boolean rejected=false;
        try {EisInbox.xml(unsafe.getBytes(StandardCharsets.UTF_8));}catch(Exception ex){rejected=true;}
        assert rejected:"XML external entities must be rejected";
        Path temp=Files.createTempDirectory("eis-test-");
        try {
            Files.writeString(temp.resolve("sample.csv"),csv);
            assert EisInbox.scan(temp.toString()).events().size()==1;
            Store store=new Store(temp);
            for(var ev:acts)store.event(ev);
            assert store.events().size()==2:"Two decrees must survive same-bulletin URL";
            for(var ev:acts)store.event(ev);
            assert store.events().size()==2:"Idempotent reimport";
            store.event(tenders.get(0));
            assert "PROCUREMENT".equals(store.events().get(0).get("stage"));
            assert "KAZAN_MENTION".equals(store.events().get(0).get("locationScope"));
            assert store.view().get(0).get("relevance").equals("UNLOCATED");
        }finally {
            try(var files=Files.list(temp)){files.forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});}
            Files.delete(temp);
        }
        System.out.println("RoadSourcesTest: source extraction, deduplication, geography and imports passed.");
    }
}
