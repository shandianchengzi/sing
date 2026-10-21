package com.shandian.sing;
import java.util.*;
import java.util.regex.*;
final class Lrc {
 static final class Line { final long ms; final String text; Line(long m,String t){ms=m;text=t;} }
 final ArrayList<Line> lines=new ArrayList<>(); long offset;
 Lrc(String raw){
  Matcher o=Pattern.compile("\\[offset:([+-]?\\d+)\\]",Pattern.CASE_INSENSITIVE).matcher(raw); if(o.find())offset=Long.parseLong(o.group(1));
  Pattern p=Pattern.compile("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
  for(String row:raw.split("\\r?\\n")){Matcher m=p.matcher(row); ArrayList<Long> times=new ArrayList<>();int end=0;
   while(m.find()){String f=m.group(3);long frac=f==null?0:Long.parseLong((f+"000").substring(0,3));times.add(Long.parseLong(m.group(1))*60000+Long.parseLong(m.group(2))*1000+frac);end=m.end();}
   String t=row.substring(end).trim();for(long ms:times)lines.add(new Line(ms,t));
  } Collections.sort(lines,(a,b)->Long.compare(a.ms,b.ms));
 }
 int at(long ms){int lo=0,hi=lines.size()-1,ans=-1;while(lo<=hi){int mid=(lo+hi)>>>1;if(lines.get(mid).ms<=ms+offset){ans=mid;lo=mid+1;}else hi=mid-1;}return ans;}
}
