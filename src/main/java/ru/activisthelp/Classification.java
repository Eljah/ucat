package ru.activisthelp;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Classification {
    private Classification() {}
    public static String stage(String input) {
        String s=input.toLowerCase(Locale.ROOT);
        if(hit(s,"оповещени.*обсужден|общественн.*обсужден|публичн.*слушан|встреч.*жител"))return "DISCUSSION";
        if(hit(s,"о подготовке|подготовить проект|проектирован|изыскани|задани.*проект"))return "PREPARATION";
        if(hit(s,"закупк|аукцион|тендер|определение поставщик|извещени.*закуп"))return "PROCUREMENT";
        if(hit(s,"об утверждении|утвердить проект|разрешени.*строитель|сервитут"))return "APPROVAL";
        if(hit(s,"начал.*работ|ремонт|реконструкц|строительств|благоустройств"))return "WORKS";
        return "OTHER";
    }
    public static boolean relevant(String title) {
        return hit(title.toLowerCase(Locale.ROOT),
            "планиров|межеван|обсужден|слушан|подготовк.*проект|реконструк|ремонт|строительств|благоустройств|дорог|тротуар|парков|сквер|зелен.*насажден|сервитут|земельн.*участ|инженерн.*изыскан|водоотведени|капитальн.*ремонт|пешеход|велодорож|озеленени");
    }
    private static boolean hit(String s,String regex){return Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(s).find();}
}
