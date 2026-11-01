package com.x3card.pet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 法定节假日放假与调休安排，同网页版 pet/pages/calendar/holidays.js 和日历页里的假日索引、下个假期。
 *  国务院办公厅每年 11 月前后发布次年安排，发布后在 DATA 里补一年。没有数据的年份只显示节日，不标"休/班"。 */
public final class Holidays {
  private Holidays() {}

  // 每条：名字、放假起止日（含）、调休上班日（可以没有）
  // 2026 年来源：国办发明电〔2025〕7 号《国务院办公厅关于 2026 年部分节假日安排的通知》
  private static final String[][] DATA = {
    // 2025
    { "元旦", "2025-01-01", "2025-01-01" },
    { "春节", "2025-01-28", "2025-02-04", "2025-01-26", "2025-02-08" },
    { "清明节", "2025-04-04", "2025-04-06" },
    { "劳动节", "2025-05-01", "2025-05-05", "2025-04-27" },
    { "端午节", "2025-05-31", "2025-06-02" },
    { "国庆节、中秋节", "2025-10-01", "2025-10-08", "2025-09-28", "2025-10-11" },
    // 2026
    { "元旦", "2026-01-01", "2026-01-03", "2026-01-04" },
    { "春节", "2026-02-15", "2026-02-23", "2026-02-14", "2026-02-28" },
    { "清明节", "2026-04-04", "2026-04-06" },
    { "劳动节", "2026-05-01", "2026-05-05", "2026-05-09" },
    { "端午节", "2026-06-19", "2026-06-21" },
    { "中秋节", "2026-09-25", "2026-09-27" },
    { "国庆节", "2026-10-01", "2026-10-07", "2026-09-20", "2026-10-10" },
  };

  /** 某天是放假还是调休上班 */
  public static final class Mark {
    public final boolean off, work;
    public final String name;
    public final Lunar.Ymd from, to; // 放假的起止日（调休上班时为 null）
    Mark(boolean off, String name, Lunar.Ymd from, Lunar.Ymd to) { this.off = off; this.work = !off; this.name = name; this.from = from; this.to = to; }
  }

  /** 下个假期（nextHoliday 的结果） */
  public static final class Next {
    public final String name;
    public final boolean now;      // 正在放假：day 第几天、left 还剩几天、total 共几天
    public final boolean estimate; // 没有放假数据，按节日日期估算（total 为 0）
    public final int day, left, total;
    public final int days;         // 离假期第一天还有几天（正在放假时为 0）
    public final Lunar.Ymd date;   // 假期第一天（正在放假时为 null）
    Next(String name, boolean now, boolean estimate, int day, int left, int total, int days, Lunar.Ymd date) {
      this.name = name; this.now = now; this.estimate = estimate; this.day = day; this.left = left; this.total = total; this.days = days; this.date = date;
    }
  }

  private static final class Range {
    final String name;
    final int from, to;
    Range(String name, int from, int to) { this.name = name; this.from = from; this.to = to; }
  }

  // 'yyyy-MM-dd' → 放假 / 调休；放假区间按起始日排好
  private static final Map<String, Mark> HOL = new HashMap<>();
  private static final List<Range> RANGES = new ArrayList<>();

  static {
    for (String[] h : DATA) {
      int t0 = parse(h[1]), t1 = parse(h[2]);
      RANGES.add(new Range(h[0], t0, t1));
      Lunar.Ymd from = Lunar.ofEpochDay(t0), to = Lunar.ofEpochDay(t1);
      for (int t = t0; t <= t1; t++) { Lunar.Ymd d = Lunar.ofEpochDay(t); HOL.put(key(d.y, d.m, d.d), new Mark(true, h[0], from, to)); }
      for (int i = 3; i < h.length; i++) HOL.put(h[i], new Mark(false, h[0], null, null));
    }
    Collections.sort(RANGES, new Comparator<Range>() {
      @Override public int compare(Range a, Range b) { return Integer.compare(a.from, b.from); }
    });
  }

  private static int parse(String s) {
    String[] p = s.split("-");
    return Lunar.epochDay(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
  }

  private static String pad(int n) { return (n < 10 ? "0" : "") + n; }
  private static String key(int y, int m, int d) { return y + "-" + pad(m) + "-" + pad(d); }

  /** 这天放假或调休上班，都不是返回 null */
  public static Mark at(int y, int m, int d) { return HOL.get(key(y, m, d)); }

  /** 下个假期：有放假数据时用数据（含放几天、正在放假第几天）；没有数据的年份按法定节日估算。两者取更早的那个 */
  public static Next next(int y, int m, int d) {
    int t = Lunar.epochDay(y, m, d);
    Next data = null;
    int dataT = 0;
    for (int i = 0; i < RANGES.size() && data == null; i++) {
      Range r = RANGES.get(i);
      if (t >= r.from && t <= r.to) return new Next(r.name, true, false, t - r.from + 1, r.to - t, r.to - r.from + 1, 0, null);
      if (r.from > t) { data = new Next(r.name, false, false, 0, 0, r.to - r.from + 1, r.from - t, Lunar.ofEpochDay(r.from)); dataT = r.from; }
    }
    Next est = estimate(t);
    if (data != null && (est == null || dataT <= Lunar.epochDay(est.date.y, est.date.m, est.date.d))) return data;
    return est;
  }

  private static final String[] EST_NAMES = { "元旦", "春节", "清明节", "劳动节", "端午节", "中秋节", "国庆节" };

  /** 按法定节日的日期估算下个假期（放假安排以当年公布为准） */
  private static Next estimate(int t) {
    Lunar.Ymd d = Lunar.ofEpochDay(t);
    String bestName = null;
    Lunar.Ymd bestDate = null;
    int bestT = 0;
    for (int y = d.y; y <= d.y + 1 && y <= Lunar.MAX_YEAR; y++) {
      Lunar.Ymd qm = null;
      for (Lunar.Term x : Lunar.termsOfYear(y)) if (x.name.equals("清明")) { qm = new Lunar.Ymd(x.y, x.m, x.d); break; }
      Lunar.Ymd[] list = { new Lunar.Ymd(y, 1, 1), Lunar.toSolar(y, 1, 1, false), qm,
        new Lunar.Ymd(y, 5, 1), Lunar.toSolar(y, 5, 5, false), Lunar.toSolar(y, 8, 15, false), new Lunar.Ymd(y, 10, 1) };
      for (int j = 0; j < list.length; j++) {
        Lunar.Ymd s = list[j];
        if (s == null) continue;
        int ts = Lunar.epochDay(s.y, s.m, s.d);
        if (ts >= t && (bestName == null || ts < bestT)) { bestName = EST_NAMES[j]; bestT = ts; bestDate = s; }
      }
    }
    if (bestName == null) return null;
    return new Next(bestName, false, true, 0, 0, 0, bestT - t, bestDate);
  }
}
