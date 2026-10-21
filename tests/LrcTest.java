package com.shandian.sing;

public class LrcTest {
  public static void main(String[] args) {
    Lrc l = new Lrc("[offset:100]\n[00:02.123][00:04.5]双时间\n[00:01.01]较早\n[ar:歌手]\n[00:03]整秒");
    check(l.lines.size() == 4);
    check(l.lines.get(0).ms == 1010);
    check(l.lines.get(3).ms == 4500);
    check(l.at(909) == -1);
    check(l.at(910) == 0);
    check(l.at(2023) == 1);
    check(l.at(99999) == 3);
    check(l.lines.get(1).text.equals("双时间"));
    check(new Lrc("[ar:元数据]").lines.isEmpty());
    System.out.println("LRC tests passed");
  }

  static void check(boolean b) {
    if (!b) throw new AssertionError();
  }
}
