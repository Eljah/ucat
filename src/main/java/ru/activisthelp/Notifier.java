package ru.activisthelp;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Optional Telegram notifications. Never stores bot credentials in the project data file. */
public final class Notifier {
    private final String token=System.getenv().getOrDefault("ACTIVIST_TELEGRAM_TOKEN","");
    private final String chatId=System.getenv().getOrDefault("ACTIVIST_TELEGRAM_CHAT_ID","");
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public boolean enabled(){return !token.isBlank()&&!chatId.isBlank();}
    /** One message per scan rather than flooding with individual documents. */
    public void notifyBatch(List<Map<String,Object>> events) {
        if(!enabled()||events.isEmpty())return;
        List<Map<String,Object>> strong=new ArrayList<>(),needsReview=new ArrayList<>();
        for(var e:events) {
            String relevance=Json.string(e.get("relevance"));
            if(relevance.equals("MATCH")||relevance.equals("HINT"))strong.add(e);
            else needsReview.add(e);
        }
        StringBuilder text=new StringBuilder("Помощь активисту · Казань\n");
        text.append("Впервые обнаружено при проверке: ").append(events.size()).append(" записей. Это не обязательно дата публикации!\n\n");
        if(!strong.isEmpty()) {
            text.append("Совпадения и текстовые подсказки (проверьте географию):\n");
            for(var e:strong.subList(0,Math.min(6,strong.size())))text.append("• ").append(cut(e.get("title"),115)).append('\n');
        }
        if(!needsReview.isEmpty()) {
            text.append("\nБез определённой связи с полигоном: ").append(needsReview.size()).append("; проверьте ленту.\n");
            for(var e:needsReview.subList(0,Math.min(3,needsReview.size())))text.append("• ").append(cut(e.get("title"),90)).append('\n');
        }
        text.append("\nПервичная информация: проверяйте оригиналы документов и сроки участия.");
        String form="chat_id="+enc(chatId)+"&text="+enc(text.toString());
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot"+token+"/sendMessage"))
                .timeout(Duration.ofSeconds(12)).header("Content-Type","application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form,StandardCharsets.UTF_8)).build();
            HttpResponse<Void> res=http.send(request,HttpResponse.BodyHandlers.discarding());
            if(res.statusCode()!=200)System.err.println("Telegram notification: HTTP "+res.statusCode());
        }catch(Exception e){System.err.println("Telegram delivery failed: "+e.getMessage());}
    }
    private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static String cut(Object o,int max){String s=Json.string(o);return s.length()<=max?s:s.substring(0,max-1)+"…";}
}
