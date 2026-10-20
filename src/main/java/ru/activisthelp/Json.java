package ru.activisthelp;

import java.util.*;

/** Minimal dependency-free JSON reader/writer. Only JSON-compatible types are returned. */
public final class Json {
    private Json() {}
    public static Object parse(String source) {
        Parser p = new Parser(source);
        Object result = p.value();
        p.whitespace();
        if (p.pos != source.length()) throw new IllegalArgumentException("Trailing characters in JSON");
        return result;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object o) {
        if (!(o instanceof Map<?,?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String,Object>)o;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object o) {
        if (!(o instanceof List<?>)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>)o;
    }
    public static String string(Object o) { return o == null ? "" : String.valueOf(o); }
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        encode(value, sb);
        return sb.toString();
    }
    private static void encode(Object v, StringBuilder out) {
        if (v == null) { out.append("null"); return; }
        if (v instanceof String str) {
            out.append('"');
            for (int i=0;i<str.length();i++) {
                char ch=str.charAt(i);
                switch(ch) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (ch<0x20) out.append(String.format("\\u%04x", (int)ch));
                        else out.append(ch);
                    }
                }
            }
            out.append('"'); return;
        }
        if (v instanceof Number || v instanceof Boolean) { out.append(v); return; }
        if (v instanceof Map<?,?> map) {
            out.append('{'); boolean first=true;
            for(var e:map.entrySet()) { if(!first)out.append(',');first=false;
                encode(String.valueOf(e.getKey()),out);out.append(':');encode(e.getValue(),out);
            }
            out.append('}'); return;
        }
        if (v instanceof Collection<?> list) {
            out.append('[');boolean first=true;
            for(Object item:list) { if(!first)out.append(',');first=false;encode(item,out); }
            out.append(']');return;
        }
        throw new IllegalArgumentException("Not JSON-compatible: " + v.getClass());
    }
    private static final class Parser {
        final String s; int pos;
        Parser(String s) { this.s=Objects.requireNonNull(s); }
        void whitespace() { while(pos<s.length() && Character.isWhitespace(s.charAt(pos)))pos++; }
        char peek() { whitespace(); if(pos>=s.length())throw error("Unexpected end");return s.charAt(pos); }
        IllegalArgumentException error(String msg) { return new IllegalArgumentException(msg+" at position "+pos); }
        void expect(char ch) { if(peek()!=ch)throw error("Expected '"+ch+"'");pos++; }
        Object value() {
            switch(peek()) {
                case '{': return object();
                case '[': return list();
                case '"': return str();
                case 't': literal("true"); return true;
                case 'f': literal("false"); return false;
                case 'n': literal("null"); return null;
                default: return number();
            }
        }
        void literal(String word) { if(!s.startsWith(word,pos))throw error("Expected "+word);pos+=word.length(); }
        Map<String,Object> object() {
            expect('{');Map<String,Object> out=new LinkedHashMap<>();
            if(peek()=='}'){pos++;return out;}
            while(true) {String key=str();expect(':');out.put(key,value());char sep=peek();pos++;
                if(sep=='}')return out;if(sep!=',')throw error("Expected ',' or '}'");
            }
        }
        List<Object> list() {
            expect('[');List<Object> out=new ArrayList<>();
            if(peek()==']'){pos++;return out;}
            while(true) {out.add(value());char sep=peek();pos++;
                if(sep==']')return out;if(sep!=',')throw error("Expected ',' or ']'");
            }
        }
        String str() {
            expect('"');StringBuilder out=new StringBuilder();
            while(pos<s.length()) {
                char c=s.charAt(pos++);
                if(c=='"')return out.toString();
                if(c=='\\') {
                    if(pos>=s.length())throw error("Unfinished escape");
                    c=s.charAt(pos++);
                    switch(c) {
                        case '"','\\','/' -> out.append(c);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if(pos+4>s.length())throw error("Invalid unicode escape");
                            try {out.append((char)Integer.parseInt(s.substring(pos,pos+4),16));}catch(NumberFormatException e){throw error("Invalid unicode escape");}
                            pos+=4;
                        }
                        default -> throw error("Invalid escape");
                    }
                } else {
                    if(c<0x20)throw error("Control character in string");
                    out.append(c);
                }
            }
            throw error("Unfinished string");
        }
        Number number() {
            whitespace();int start=pos;
            while(pos<s.length() && "0123456789+-.eE".indexOf(s.charAt(pos))>=0)pos++;
            if(pos==start)throw error("Expected value");
            String num=s.substring(start,pos);
            try {
                if(num.contains(".")||num.contains("e")||num.contains("E"))return Double.valueOf(num);
                return Long.valueOf(num);
            }
            catch(NumberFormatException e){throw error("Invalid number");}
        }
    }
    public static Map<String,Object> obj(Object...pairs) {
        Map<String,Object> m=new LinkedHashMap<>();
        if(pairs.length%2!=0)throw new IllegalArgumentException("Odd number of JSON pairs");
        for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);
        return m;
    }
}
