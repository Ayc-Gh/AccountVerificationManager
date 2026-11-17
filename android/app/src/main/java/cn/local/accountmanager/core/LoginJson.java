package cn.local.accountmanager.core;

import java.util.*;

/** Small bounded JSON codec for OAuth, independent of Android's stubbed test APIs. */
public final class LoginJson {
    private LoginJson() {}
    public static Map<String,Object> object(Object... fields) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<fields.length;i+=2) result.put((String)fields[i], fields[i+1]);
        return result;
    }
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder b = new StringBuilder("\"");
            for (int i=0;i<text.length();i++) { char c=text.charAt(i); if(c=='"'||c=='\\') b.append('\\').append(c); else if(c<32) b.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else b.append(c); }
            return b.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        List<String> parts = new ArrayList<>();
        if (value instanceof Map<?,?> map) { for (var entry:map.entrySet()) parts.add(encode(entry.getKey().toString())+":"+encode(entry.getValue())); return "{"+String.join(",",parts)+"}"; }
        if (value instanceof Iterable<?> values) { for(Object item:values) parts.add(encode(item)); return "["+String.join(",",parts)+"]"; }
        throw new IllegalArgumentException("无效 JSON 值。");
    }
    public static Object decode(String text) {
        if(text==null||text.length()>1048576) throw new IllegalArgumentException("授权响应无效或过大。");
        Parser p=new Parser(text); Object value=p.value(0);p.space();if(p.i!=text.length())throw new IllegalArgumentException("JSON 格式无效。");return value;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) {return value instanceof Map<?,?>?(Map<String,Object>)value:Collections.emptyMap();}
    public static String string(Map<String,Object> map,String key){Object v=map.get(key);return v instanceof String?(String)v:"";}
    private static final class Parser {
        final String s; int i; Parser(String s){this.s=s;}
        void space(){while(i<s.length()&&" \r\n\t".indexOf(s.charAt(i))>=0)i++;}
        char next(){if(i>=s.length())throw new IllegalArgumentException("JSON 不完整。");return s.charAt(i++);}
        Object value(int depth){
            if(depth>32)throw new IllegalArgumentException("JSON 嵌套过深。");space();char c=next();
            if(c=='"')return text();
            if(c=='{'){Map<String,Object> m=new LinkedHashMap<>();space();if(i<s.length()&&s.charAt(i)=='}'){i++;return m;}while(true){space();if(next()!='"')throw new IllegalArgumentException("JSON 字段无效。");String k=text();space();if(next()!=':'||m.containsKey(k))throw new IllegalArgumentException("JSON 字段无效或重复。");m.put(k,value(depth+1));space();char end=next();if(end=='}')return m;if(end!=',')throw new IllegalArgumentException("JSON 字段无效。");}}
            if(c=='['){List<Object> list=new ArrayList<>();space();if(i<s.length()&&s.charAt(i)==']'){i++;return list;}while(true){list.add(value(depth+1));space();char end=next();if(end==']')return list;if(end!=',')throw new IllegalArgumentException("JSON 数组无效。");}}
            if(c=='t'&&s.startsWith("rue",i)){i+=3;return true;}if(c=='f'&&s.startsWith("alse",i)){i+=4;return false;}if(c=='n'&&s.startsWith("ull",i)){i+=3;return null;}
            int start=i-1;while(i<s.length()&&"0123456789.eE+-".indexOf(s.charAt(i))>=0)i++;String n=s.substring(start,i);if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("JSON 数字无效。");try{if(n.contains(".")||n.contains("e")||n.contains("E"))return Double.valueOf(n);return Long.valueOf(n);}catch(NumberFormatException e){throw new IllegalArgumentException("JSON 数字超出范围。");}
        }
        String text(){StringBuilder b=new StringBuilder();while(true){char c=next();if(c=='"')return b.toString();if(c<32)throw new IllegalArgumentException("JSON 字符串无效。");if(c!='\\'){b.append(c);continue;}char e=next();switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(i+4>s.length())throw new IllegalArgumentException("JSON 转义无效。");try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException ex){throw new IllegalArgumentException("JSON 转义无效。");}i+=4;}default->throw new IllegalArgumentException("JSON 转义无效。");}}}
    }
}
